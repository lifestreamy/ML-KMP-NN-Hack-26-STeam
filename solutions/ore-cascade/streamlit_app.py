"""
Streamlit-приложение: каскад классификации руды
(оталькованная / рядовая / трудная).

Stage 1 — классический pipeline (KMeans + анализ текстуры талька), без нейросети.
Stage 2 — обученная CNN (ryadovie / trudnie), загружается из чекпоинта .pth.

Запуск:
    streamlit run streamlit_app.py
"""

import os
import io
import json
import warnings
warnings.filterwarnings('ignore')

import cv2
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from PIL import Image

import torch
import torch.nn as nn
import torch.nn.functional as F
from torchvision import transforms

import streamlit as st

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Image as RLImage, Table, TableStyle
)
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

# Встроенные шрифты ReportLab (Helvetica) не поддерживают кириллицу —
# это ограничение самих 14 стандартных PDF-шрифтов, а не вопрос установки.
# Ищем подходящий TTF-шрифт с кириллицей: сначала стандартные системные
# шрифты (Arial/Calibri на Windows, DejaVu/Liberation на Linux, Arial на
# macOS), и только если ничего не нашлось — используем шрифт, который лежит
# прямо в проекте (fonts/DejaVuSans.ttf), чтобы приложение работало в любом
# случае.
_FONTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'fonts')

_SYSTEM_FONT_CANDIDATES = [
    # (обычный, жирный)
    (r'C:\Windows\Fonts\arial.ttf', r'C:\Windows\Fonts\arialbd.ttf'),
    (r'C:\Windows\Fonts\calibri.ttf', r'C:\Windows\Fonts\calibrib.ttf'),
    ('/System/Library/Fonts/Supplemental/Arial.ttf', '/System/Library/Fonts/Supplemental/Arial Bold.ttf'),
    ('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'),
    ('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',
     '/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf'),
]

_FONT_REGULAR = 'DejaVuSans'
_FONT_BOLD = 'DejaVuSans-Bold'
_CYRILLIC_FONT_ERROR = None
_CYRILLIC_FONT_SOURCE = None


def _try_register(regular_path, bold_path, name_regular, name_bold):
    if not (os.path.exists(regular_path) and os.path.exists(bold_path)):
        return False
    pdfmetrics.registerFont(TTFont(name_regular, regular_path))
    pdfmetrics.registerFont(TTFont(name_bold, bold_path))
    return True


_CYRILLIC_FONT_OK = False
for _regular_path, _bold_path in _SYSTEM_FONT_CANDIDATES:
    try:
        if _try_register(_regular_path, _bold_path, _FONT_REGULAR, _FONT_BOLD):
            _CYRILLIC_FONT_OK = True
            _CYRILLIC_FONT_SOURCE = _regular_path
            break
    except Exception as _font_exc:
        _CYRILLIC_FONT_ERROR = str(_font_exc)

if not _CYRILLIC_FONT_OK:
    # Запасной вариант — шрифт, который лежит прямо в проекте.
    try:
        _bundled_regular = os.path.join(_FONTS_DIR, 'DejaVuSans.ttf')
        _bundled_bold = os.path.join(_FONTS_DIR, 'DejaVuSans-Bold.ttf')
        if _try_register(_bundled_regular, _bundled_bold, _FONT_REGULAR, _FONT_BOLD):
            _CYRILLIC_FONT_OK = True
            _CYRILLIC_FONT_SOURCE = _bundled_regular
    except Exception as _font_exc:
        _CYRILLIC_FONT_ERROR = str(_font_exc)

if not _CYRILLIC_FONT_OK:
    _FONT_REGULAR = 'Helvetica'
    _FONT_BOLD = 'Helvetica-Bold'

SEED = 42
torch.manual_seed(SEED)
np.random.seed(SEED)

MODEL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'model')
DEFAULT_STAGE2_MODEL_PATH = os.path.join(MODEL_DIR, 'cnn_two_class_final.pth')

CLASS_NAMES = ['ryadovie', 'trudnie']
POSITIVE_CLASS_IDX = 1

LABEL_RU = {
    'otalkovanie': 'Оталькованная',
    'ryadovie': 'Рядовая',
    'trudnie': 'Труднообогатимая',
}

LABEL_COLOR = {
    'otalkovanie': '#8e44ad',
    'ryadovie': '#27ae60',
    'trudnie': '#c0392b',
}


# =========================================================
# Устройство (CUDA / DirectML / CPU)
# =========================================================

@st.cache_resource(show_spinner=False)
def get_device():
    if torch.cuda.is_available():
        name = torch.cuda.get_device_name(0)
        return torch.device('cuda'), f'CUDA ({name})'

    try:
        import torch_directml
        if torch_directml.is_available():
            device = torch_directml.device()
            return device, f'DirectML ({device})'
    except ImportError:
        pass

    return torch.device('cpu'), 'CPU (GPU не найден или не настроен — будет работать медленнее)'


# =========================================================
# Stage 1 — сегментация и детекция талька
# =========================================================

def load_image_bgr_from_bytes(file_bytes, max_side=1600):
    arr = np.frombuffer(file_bytes, dtype=np.uint8)
    img_bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img_bgr is None:
        raise ValueError('Не удалось прочитать изображение')
    h, w = img_bgr.shape[:2]
    scale = min(1.0, max_side / max(h, w))
    if scale < 1.0:
        img_bgr = cv2.resize(img_bgr, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    return img_bgr


def detect_blue_annotation(img_bgr):
    b, g, r = cv2.split(img_bgr)
    return ((b.astype(int) - r.astype(int) > 60) &
             (b.astype(int) - g.astype(int) > 60) &
             (b > 150))


def kmeans_zone_masks(img_bgr, blue_mask, k=4, scale=0.5):
    from sklearn.cluster import KMeans

    h, w = img_bgr.shape[:2]
    lab_full = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2LAB)
    small_bgr = cv2.resize(img_bgr, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    small_blue = cv2.resize(blue_mask.astype(np.uint8), None, fx=scale, fy=scale,
                             interpolation=cv2.INTER_NEAREST).astype(bool)
    small_lab = cv2.cvtColor(small_bgr, cv2.COLOR_BGR2LAB)
    pixels = small_lab.reshape(-1, 3).astype(np.float32)
    valid = ~small_blue.reshape(-1)

    km = KMeans(n_clusters=k, n_init=10, random_state=42)
    km.fit(pixels[valid])

    full_pixels = lab_full.reshape(-1, 3).astype(np.float32)
    full_labels = km.predict(full_pixels).reshape(h, w)
    order = np.argsort(km.cluster_centers_[:, 0])[::-1]  # светлый -> тёмный

    sulfide_mask = np.isin(full_labels, [order[0], order[1]]) & (~blue_mask)
    background_mask = (full_labels == order[2]) & (~blue_mask)
    potential_talc_mask = (full_labels == order[3]) & (~blue_mask)

    return sulfide_mask, potential_talc_mask, background_mask


def erode_mask(mask, radius):
    if radius <= 0:
        return mask
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (radius * 2 + 1, radius * 2 + 1))
    eroded = cv2.erode(mask.astype(np.uint8), kernel, iterations=1)
    return eroded.astype(bool)


def local_std_map_masked(img_bgr, mask, win=9):
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY).astype(np.float32)
    m = mask.astype(np.float32)
    gray_masked = gray * m
    count = cv2.boxFilter(m, -1, (win, win), normalize=False)
    sum_ = cv2.boxFilter(gray_masked, -1, (win, win), normalize=False)
    sum_sq = cv2.boxFilter(gray_masked * gray_masked, -1, (win, win), normalize=False)
    count_safe = np.clip(count, 1, None)
    mean = sum_ / count_safe
    var = sum_sq / count_safe - mean ** 2
    std_map = np.sqrt(np.clip(var, 0, None))
    std_map[count < (win * win) * 0.5] = 0
    std_map[~mask] = 0
    return std_map


def auto_std_threshold(std_map_masked, mask):
    vals = std_map_masked[mask]
    if vals.size == 0:
        raise ValueError('Нет валидных пикселей внутри маски')

    v_min, v_max = vals.min(), vals.max()
    if v_max <= v_min:
        return v_max

    vals_scaled = ((vals - v_min) / (v_max - v_min) * 255).astype(np.uint8)
    otsu_thr_scaled, _ = cv2.threshold(vals_scaled, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    otsu_thr = v_min + (otsu_thr_scaled / 255.0) * (v_max - v_min)
    return otsu_thr


def extract_inclusion_candidates_auto(std_map_masked, mask, open_radius=2):
    thr = auto_std_threshold(std_map_masked, mask)
    candidate_mask = (std_map_masked > thr) & mask

    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (open_radius * 2 + 1, open_radius * 2 + 1))
    cleaned = cv2.morphologyEx(candidate_mask.astype(np.uint8), cv2.MORPH_OPEN, kernel)

    return cleaned.astype(bool), thr


def auto_sigma_by_contrast(candidate_mask, sigma_grid=(5, 8, 10, 15, 20, 25, 35, 50, 70, 100)):
    results = []
    for sigma in sigma_grid:
        density = cv2.GaussianBlur(candidate_mask.astype(np.float32), (0, 0), sigmaX=sigma)
        vals = density[density > density.max() * 0.01]
        if vals.size == 0:
            continue
        cv_score = vals.std() / (vals.mean() + 1e-9)
        results.append((sigma, cv_score))

    if not results:
        return sigma_grid[0], np.array(sigma_grid), np.zeros(len(sigma_grid))

    sigmas = np.array([r[0] for r in results])
    scores = np.array([r[1] for r in results])
    best_sigma = sigmas[np.argmax(scores)]
    return best_sigma, sigmas, scores


def candidates_to_density_zone(candidate_mask, sigma=0):
    density = cv2.GaussianBlur(candidate_mask.astype(np.float32), (0, 0), sigmaX=sigma)
    return density


def density_to_mask(density, candidate_mask, percentile=50, min_area=15):
    significant = density[density > density.max() * 0.01]
    if significant.size == 0:
        return np.zeros_like(density, dtype=bool)

    thresh = np.percentile(significant, percentile)
    mask = density >= thresh

    mask_u8 = mask.astype(np.uint8)
    n_labels, labels, stats, _ = cv2.connectedComponentsWithStats(mask_u8, connectivity=8)
    clean_mask = np.zeros_like(mask, dtype=bool)
    for i in range(1, n_labels):
        if stats[i, cv2.CC_STAT_AREA] >= min_area:
            clean_mask[labels == i] = True

    return clean_mask


def run_stage1(img_bgr, sample_name, radius, density_percentile, density_min_area, stage1_threshold):
    blue_mask = detect_blue_annotation(img_bgr)

    sulfide_mask, potential_talc_mask, background_mask = kmeans_zone_masks(img_bgr, blue_mask, k=4)

    talc_core_mask = erode_mask(potential_talc_mask, radius=radius)
    std_map_masked = local_std_map_masked(img_bgr, talc_core_mask, win=9)

    vals = std_map_masked[talc_core_mask]
    if vals.size == 0:
        raise ValueError(f'[{sample_name}] Пустая зона талька — нечего анализировать')

    inclusion_mask, thr = extract_inclusion_candidates_auto(std_map_masked, talc_core_mask, open_radius=2)
    best_sigma, sigmas, scores = auto_sigma_by_contrast(inclusion_mask)
    density = candidates_to_density_zone(inclusion_mask, sigma=best_sigma)
    zone_mask = density_to_mask(density, inclusion_mask, percentile=density_percentile, min_area=density_min_area)

    pct_inclusions_in_talc = 100 * inclusion_mask.sum() / inclusion_mask.size
    pct_final_zone = 100 * zone_mask.mean()

    pred_label = 'otalkovanie' if pct_inclusions_in_talc > stage1_threshold else 'not_otalkovanie'

    return {
        'sample': sample_name,
        'pct_sulfide': 100 * sulfide_mask.sum() / sulfide_mask.size,
        'pct_potential_talc': 100 * potential_talc_mask.sum() / potential_talc_mask.size,
        'pct_background': 100 * background_mask.sum() / background_mask.size,
        'otsu_std_thr': thr,
        'pct_inclusions_in_talc': pct_inclusions_in_talc,
        'best_sigma': best_sigma,
        'pct_final_zone': pct_final_zone,
        'stage1_pred': pred_label,
        'img_bgr': img_bgr,
        'sulfide_mask': sulfide_mask,
        'potential_talc_mask': potential_talc_mask,
        'background_mask': background_mask,
        'density': density,
        'zone_mask': zone_mask,
    }


# =========================================================
# Stage 2 — CNN (ryadovie / trudnie)
# =========================================================

class CNN(nn.Module):
    def __init__(self, num_classes=2, img_size=128):
        super(CNN, self).__init__()
        self.conv1 = nn.Conv2d(in_channels=3, out_channels=32, kernel_size=3)
        self.pool = nn.MaxPool2d(kernel_size=2, stride=2)
        self.conv2 = nn.Conv2d(in_channels=32, out_channels=32, kernel_size=3)

        with torch.no_grad():
            dummy = torch.zeros(1, 3, img_size, img_size)
            dummy = self.pool(F.relu(self.conv1(dummy)))
            dummy = self.pool(F.relu(self.conv2(dummy)))
            flatten_size = dummy.numel()

        self.fc1 = nn.Linear(flatten_size, 128)
        self.dropout = nn.Dropout(0.5)
        self.fc2 = nn.Linear(128, num_classes)

    def forward(self, x):
        x = self.pool(F.relu(self.conv1(x)))
        x = self.pool(F.relu(self.conv2(x)))
        x = torch.flatten(x, 1)
        x = F.relu(self.fc1(x))
        x = self.dropout(x)
        x = self.fc2(x)
        return x


def resize_with_padding(img, target_size=512):
    img = img.copy()
    img.thumbnail((target_size, target_size), Image.LANCZOS)
    new_img = Image.new('RGB', (target_size, target_size), (0, 0, 0))
    paste_x = (target_size - img.width) // 2
    paste_y = (target_size - img.height) // 2
    new_img.paste(img, (paste_x, paste_y))
    return new_img


stage2_base_transform = transforms.Compose([
    transforms.ToTensor(),
    transforms.Normalize((0.5, 0.5, 0.5), (0.5, 0.5, 0.5)),
])


@st.cache_resource(show_spinner='Загрузка модели Stage 2...')
def load_stage2_model(model_path, img_size, device_placeholder):
    device, device_str = get_device()
    model = CNN(num_classes=len(CLASS_NAMES), img_size=img_size).to(device)
    state_dict = torch.load(model_path, map_location=device, weights_only=False)
    model.load_state_dict(state_dict)
    model.eval()
    return model, device, device_str


def run_stage2(pil_img, model, device, img_size, stage2_threshold):
    img = resize_with_padding(pil_img, target_size=img_size)
    img_tensor = stage2_base_transform(img).unsqueeze(0).to(device)

    with torch.no_grad():
        probs = F.softmax(model(img_tensor), dim=1)

    prob_trudnie = probs[0, POSITIVE_CLASS_IDX].item()
    pred_idx = int(prob_trudnie > stage2_threshold)
    pred_class = CLASS_NAMES[pred_idx]

    return pred_class, prob_trudnie


# =========================================================
# Полный каскад
# =========================================================

def predict_sample(file_bytes, sample_name, cfg, model, device):
    img_bgr = load_image_bgr_from_bytes(file_bytes)

    stage1_result = run_stage1(
        img_bgr, sample_name,
        radius=cfg['RADIUS'],
        density_percentile=cfg['DENSITY_PERCENTILE'],
        density_min_area=cfg['DENSITY_MIN_AREA'],
        stage1_threshold=cfg['STAGE1_THRESHOLD'],
    )

    if stage1_result['stage1_pred'] == 'otalkovanie':
        return {
            'sample': sample_name,
            'final_label': 'otalkovanie',
            'stage1_details': stage1_result,
            'stage2_pred': None,
            'stage2_prob_trudnie': None,
        }

    pil_img = Image.open(io.BytesIO(file_bytes)).convert('RGB')
    stage2_pred, stage2_prob = run_stage2(pil_img, model, device, cfg['IMG_SIZE'], cfg['STAGE2_THRESHOLD'])

    return {
        'sample': sample_name,
        'final_label': stage2_pred,
        'stage1_details': stage1_result,
        'stage2_pred': stage2_pred,
        'stage2_prob_trudnie': stage2_prob,
    }


# =========================================================
# Визуализация
# =========================================================

def fig_zones(stage1_result):
    img_bgr = stage1_result['img_bgr']
    sulfide_mask = stage1_result['sulfide_mask']
    potential_talc_mask = stage1_result['potential_talc_mask']

    overlay = img_bgr.copy()
    overlay[sulfide_mask] = (0, 200, 0)
    overlay[potential_talc_mask] = (200, 0, 0)
    blended = cv2.addWeighted(img_bgr, 0.55, overlay, 0.45, 0)

    fig, ax = plt.subplots(figsize=(6, 5))
    ax.imshow(cv2.cvtColor(blended, cv2.COLOR_BGR2RGB))
    ax.set_title(
        f"Сульфиды (зелёный): {stage1_result['pct_sulfide']:.2f}%\n"
        f"Вероятная зона талька (синий): {stage1_result['pct_potential_talc']:.2f}%",
        fontsize=10,
    )
    ax.axis('off')
    fig.tight_layout()
    return fig


def fig_density(stage1_result):
    img_bgr = stage1_result['img_bgr']
    density = stage1_result['density']
    zone_mask = stage1_result['zone_mask']

    fig, axes = plt.subplots(1, 2, figsize=(10, 5))
    axes[0].imshow(cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB))
    axes[0].imshow(density, cmap='inferno', alpha=0.6)
    axes[0].set_title('Density')
    axes[0].axis('off')

    axes[1].imshow(cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB))
    axes[1].imshow(zone_mask, cmap='Reds', alpha=0.6)
    axes[1].set_title('Скопления талька')
    axes[1].axis('off')

    fig.tight_layout()
    return fig


# =========================================================
# Фиксированная конфигурация (значения из ноутбука)
# =========================================================

CFG = {
    'RADIUS': 0,
    'DENSITY_PERCENTILE': 45,
    'DENSITY_MIN_AREA': 15,
    'STAGE1_THRESHOLD': 8.126,
    'IMG_SIZE': 192,
    'STAGE2_THRESHOLD': 0.45,
}


# =========================================================
# Экспорт: PNG-графики и PDF-отчёт
# =========================================================

def fig_to_png_bytes(fig, dpi=150):
    buf = io.BytesIO()
    fig.savefig(buf, format='png', dpi=dpi, bbox_inches='tight')
    buf.seek(0)
    return buf.getvalue()


def build_pdf_report(result, original_png_bytes, zones_png_bytes, density_png_bytes):
    buf = io.BytesIO()
    doc = SimpleDocTemplate(
        buf, pagesize=A4,
        leftMargin=1.5 * cm, rightMargin=1.5 * cm,
        topMargin=1.5 * cm, bottomMargin=1.5 * cm,
    )

    styles = getSampleStyleSheet()
    title_style = ParagraphStyle('TitleRu', parent=styles['Title'], fontName=_FONT_BOLD, fontSize=18)
    h2_style = ParagraphStyle('H2Ru', parent=styles['Heading2'], fontName=_FONT_BOLD, spaceBefore=10)
    normal_style = ParagraphStyle('NormalRu', parent=styles['Normal'], fontName=_FONT_REGULAR)

    final_label = result['final_label']
    label_ru = LABEL_RU.get(final_label, final_label)
    details = result['stage1_details']

    story = []

    story.append(Paragraph('Отчёт по классификации руды', title_style))
    story.append(Spacer(1, 4))
    story.append(Paragraph(f"Образец: <b>{result['sample']}</b>", normal_style))
    story.append(Spacer(1, 10))

    story.append(Paragraph(f'Итоговый класс: <b>{label_ru}</b> ({final_label})', h2_style))

    stage2_prob = result['stage2_prob_trudnie']
    stage2_prob_str = f'{stage2_prob:.3f}' if stage2_prob is not None else '—  (Stage 2 не запускался)'

    table_data = [
        ['Признак', 'Значение'],
        ['pct_sulfide (%)', f"{details['pct_sulfide']:.2f}"],
        ['pct_potential_talc (%)', f"{details['pct_potential_talc']:.2f}"],
        ['pct_inclusions_in_talc (%)', f"{details['pct_inclusions_in_talc']:.2f}"],
        ['pct_final_zone (%)', f"{details['pct_final_zone']:.2f}"],
        ['STAGE1_THRESHOLD (%)', f"{CFG['STAGE1_THRESHOLD']:.3f}"],
        ['stage2_prob_trudnie', stage2_prob_str],
        ['STAGE2_THRESHOLD', f"{CFG['STAGE2_THRESHOLD']:.2f}"],
    ]
    tbl = Table(table_data, colWidths=[8 * cm, 8 * cm])
    tbl.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#2c3e50')),
        ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
        ('FONTNAME', (0, 0), (-1, 0), _FONT_BOLD),
        ('FONTNAME', (0, 1), (-1, -1), _FONT_REGULAR),
        ('GRID', (0, 0), (-1, -1), 0.5, colors.grey),
        ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, colors.HexColor('#f5f5f5')]),
        ('FONTSIZE', (0, 0), (-1, -1), 9),
        ('TOPPADDING', (0, 0), (-1, -1), 4),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
    ]))
    story.append(Spacer(1, 6))
    story.append(tbl)

    story.append(Paragraph('Исходное изображение', h2_style))
    story.append(RLImage(io.BytesIO(original_png_bytes), width=15 * cm, height=11 * cm, kind='proportional'))

    story.append(Paragraph('Зоны сульфидов / талька', h2_style))
    story.append(RLImage(io.BytesIO(zones_png_bytes), width=15 * cm, height=11 * cm, kind='proportional'))

    story.append(Paragraph('Density-карта и скопления талька', h2_style))
    story.append(RLImage(io.BytesIO(density_png_bytes), width=17 * cm, height=8.5 * cm, kind='proportional'))

    doc.build(story)
    buf.seek(0)
    return buf.getvalue()


# =========================================================
# Streamlit UI
# =========================================================

st.set_page_config(page_title='Каскад классификации руды', page_icon='⛏️', layout='wide')

st.title('⛏️ Каскад классификации руды')
st.caption('Stage 1: KMeans + текстура талька (без нейросети)  →  Stage 2: CNN (рядовая / трудная)')

cfg = CFG
model_path = DEFAULT_STAGE2_MODEL_PATH
show_plots = True

# --- загрузка модели ---
model_load_error = None
model, device, device_str = None, None, None
if os.path.exists(model_path):
    try:
        model, device, device_str = load_stage2_model(model_path, cfg['IMG_SIZE'], model_path)
    except Exception as e:
        model_load_error = str(e)
else:
    model_load_error = f'Файл не найден: {model_path}'

with st.sidebar:
    st.header('Статус')
    if device_str:
        st.success(f'Устройство: {device_str}')
    if model_load_error:
        st.error(f'Модель Stage 2 не загружена: {model_load_error}')
    if not _CYRILLIC_FONT_OK:
        st.warning(
            f'Не найден ни один шрифт с кириллицей (ни системный, ни '
            f'встроенный в проект) — текст в PDF-отчёте будет нечитаемым. '
            f'Последняя ошибка: {_CYRILLIC_FONT_ERROR}. '
            f'Проверьте, что папка fonts/ с DejaVuSans.ttf лежит рядом со streamlit_app.py.'
        )

tab_single, tab_batch = st.tabs(['📷 Одно изображение', '📁 Пакетная обработка'])

# ---------------------------------------------------------
# Одно изображение
# ---------------------------------------------------------
with tab_single:
    uploaded_file = st.file_uploader(
        'Загрузите изображение шлифа', type=['jpg', 'jpeg', 'png', 'tif', 'tiff'], key='single'
    )

    use_sample = st.checkbox('Использовать встроенный пример (sample.jpg)', value=False)

    file_bytes, sample_name = None, None
    if uploaded_file is not None:
        file_bytes = uploaded_file.getvalue()
        sample_name = os.path.splitext(uploaded_file.name)[0]
    elif use_sample:
        sample_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sample_images', 'sample.jpg')
        if os.path.exists(sample_path):
            with open(sample_path, 'rb') as f:
                file_bytes = f.read()
            sample_name = 'sample'

    if file_bytes is not None:
        if model is None:
            st.warning('Модель Stage 2 не загружена — если Stage 1 не найдёт тальк, классификация Stage 2 будет недоступна.')

        run_btn = st.button('Запустить каскад', type='primary')

        if run_btn:
            with st.spinner('Обработка изображения...'):
                try:
                    result = predict_sample(file_bytes, sample_name, cfg, model, device)
                except Exception as e:
                    st.error(f'Ошибка при обработке: {e}')
                    result = None

            if result is not None:
                final_label = result['final_label']
                color = LABEL_COLOR.get(final_label, '#333')
                st.markdown(
                    f"### Результат: <span style='color:{color}'>{LABEL_RU.get(final_label, final_label)}</span>",
                    unsafe_allow_html=True,
                )

                col1, col2, col3 = st.columns(3)
                col1.metric('pct_inclusions_in_talc', f"{result['stage1_details']['pct_inclusions_in_talc']:.2f}%")
                col2.metric('pct_final_zone', f"{result['stage1_details']['pct_final_zone']:.2f}%")
                if result['stage2_prob_trudnie'] is not None:
                    col3.metric('P(trudnie)', f"{result['stage2_prob_trudnie']:.3f}")
                else:
                    col3.metric('P(trudnie)', 'Stage 2 не запускался')

                zones_fig = fig_zones(result['stage1_details'])
                density_fig = fig_density(result['stage1_details'])
                zones_png = fig_to_png_bytes(zones_fig)
                density_png = fig_to_png_bytes(density_fig)

                if show_plots:
                    c1, c2 = st.columns(2)
                    with c1:
                        st.pyplot(zones_fig)
                        st.download_button(
                            '🖼️ Скачать график зон (PNG)',
                            data=zones_png,
                            file_name=f"{result['sample']}_zones.png",
                            mime='image/png',
                        )
                    with c2:
                        st.pyplot(density_fig)
                        st.download_button(
                            '🖼️ Скачать density-карту (PNG)',
                            data=density_png,
                            file_name=f"{result['sample']}_density.png",
                            mime='image/png',
                        )

                record = {
                    'sample': result['sample'],
                    'class': result['final_label'],
                    'pct_sulfide': result['stage1_details']['pct_sulfide'],
                    'pct_inclusions_in_talc': result['stage1_details']['pct_inclusions_in_talc'],
                    'pct_final_zone': result['stage1_details']['pct_final_zone'],
                    'stage2_prob_trudnie': result['stage2_prob_trudnie'],
                }

                dl_col1, dl_col2 = st.columns(2)
                with dl_col1:
                    st.download_button(
                        '📄 Скачать результат (JSON)',
                        data=json.dumps(record, ensure_ascii=False, indent=2),
                        file_name=f"{result['sample']}_prediction.json",
                        mime='application/json',
                    )
                with dl_col2:
                    pdf_bytes = build_pdf_report(result, file_bytes, zones_png, density_png)
                    st.download_button(
                        '📑 Скачать PDF-отчёт',
                        data=pdf_bytes,
                        file_name=f"{result['sample']}_report.pdf",
                        mime='application/pdf',
                        type='primary',
                    )

# ---------------------------------------------------------
# Пакетная обработка
# ---------------------------------------------------------
with tab_batch:
    uploaded_files = st.file_uploader(
        'Загрузите несколько изображений',
        type=['jpg', 'jpeg', 'png', 'tif', 'tiff'],
        accept_multiple_files=True,
        key='batch',
    )

    if uploaded_files:
        run_batch_btn = st.button('Запустить каскад для всех файлов', type='primary')

        if run_batch_btn:
            rows = []
            progress = st.progress(0.0, text='Обработка...')

            for i, f in enumerate(uploaded_files):
                sample_name = os.path.splitext(f.name)[0]
                try:
                    result = predict_sample(f.getvalue(), sample_name, cfg, model, device)
                    rows.append({
                        'path': f.name,
                        'sample': result['sample'],
                        'final_label': result['final_label'],
                        'pct_inclusions_in_talc': result['stage1_details']['pct_inclusions_in_talc'],
                        'pct_final_zone': result['stage1_details']['pct_final_zone'],
                        'stage2_prob_trudnie': result['stage2_prob_trudnie'],
                    })
                except Exception as e:
                    rows.append({'path': f.name, 'sample': sample_name, 'error': str(e)})

                progress.progress((i + 1) / len(uploaded_files), text=f'Обработано {i + 1}/{len(uploaded_files)}')

            progress.empty()
            df = pd.DataFrame(rows)
            st.dataframe(df, use_container_width=True)

            if 'final_label' in df.columns:
                st.bar_chart(df['final_label'].value_counts())

            st.download_button(
                'Скачать результаты (CSV)',
                data=df.to_csv(index=False).encode('utf-8-sig'),
                file_name='predictions.csv',
                mime='text/csv',
            )

st.divider()
st.caption(
    'Каскад: Stage 1 (классический CV, всегда на CPU) → если тальк не найден, '
    'Stage 2 (CNN, использует GPU при наличии).'
)
