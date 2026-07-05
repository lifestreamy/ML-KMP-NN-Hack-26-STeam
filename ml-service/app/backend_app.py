"""
Бэкенд-интерфейс для каскадной классификации руды.

Этот модуль предоставляет Python-классы для интеграции функционала
Streamlit приложения в сторонние системы.

Основные компоненты:
1. OreCascadePipeline - полный пайплайн классификации
2. Stage1Processor - обработка Stage 1 (KMeans + текстура талька)
3. Stage2Classifier - классификация Stage 2 (CNN)
4. Visualization - визуализация результатов
5. ReportGenerator - генерация PDF-отчетов
6. BatchProcessor - пакетная обработка
"""

import io
import json
import os
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

from sklearn.cluster import KMeans

from reportlab.lib.pagesizes import A4
from reportlab.lib.units import cm
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Image as RLImage, Table, TableStyle
)
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont

# Константы
SEED = 42
torch.manual_seed(SEED)
np.random.seed(SEED)

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

# Конфигурация по умолчанию
DEFAULT_CONFIG = {
    'RADIUS': 0,
    'DENSITY_PERCENTILE': 45,
    'DENSITY_MIN_AREA': 15,
    'STAGE1_THRESHOLD': 8.126,
    'IMG_SIZE': 192,
    'STAGE2_THRESHOLD': 0.45,
}


class DeviceManager:
    """Управление устройствами (CUDA / DirectML / CPU)"""

    @staticmethod
    def get_device():
        """Получить доступное устройство для вычислений"""
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


class FontManager:
    """Управление шрифтами для PDF-генерации"""

    def __init__(self):
        self.font_regular = 'DejaVuSans'
        self.font_bold = 'DejaVuSans-Bold'
        self.font_source = None
        self.fonts_ok = False
        self.font_error = None

        self._fonts_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'fonts')
        self._system_font_candidates = [
            (r'C:\Windows\Fonts\arial.ttf', r'C:\Windows\Fonts\arialbd.ttf'),
            (r'C:\Windows\Fonts\calibri.ttf', r'C:\Windows\Fonts\calibrib.ttf'),
            ('/System/Library/Fonts/Supplemental/Arial.ttf', '/System/Library/Fonts/Supplemental/Arial Bold.ttf'),
            ('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'),
            ('/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf',
             '/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf'),
        ]

        self._register_fonts()

    def _try_register(self, regular_path, bold_path, name_regular, name_bold):
        """Попытка регистрации шрифта"""
        if not (os.path.exists(regular_path) and os.path.exists(bold_path)):
            return False
        pdfmetrics.registerFont(TTFont(name_regular, regular_path))
        pdfmetrics.registerFont(TTFont(name_bold, bold_path))
        return True

    def _register_fonts(self):
        """Регистрация шрифтов с кириллицей"""
        for regular_path, bold_path in self._system_font_candidates:
            try:
                if self._try_register(regular_path, bold_path, self.font_regular, self.font_bold):
                    self.fonts_ok = True
                    self.font_source = regular_path
                    break
            except Exception as e:
                self.font_error = str(e)

        if not self.fonts_ok:
            try:
                bundled_regular = os.path.join(self._fonts_dir, 'DejaVuSans.ttf')
                bundled_bold = os.path.join(self._fonts_dir, 'DejaVuSans-Bold.ttf')
                if self._try_register(bundled_regular, bundled_bold, self.font_regular, self.font_bold):
                    self.fonts_ok = True
                    self.font_source = bundled_regular
            except Exception as e:
                self.font_error = str(e)

        if not self.fonts_ok:
            self.font_regular = 'Helvetica'
            self.font_bold = 'Helvetica-Bold'

    def get_fonts(self):
        """Получить информацию о шрифтах"""
        return {
            'regular': self.font_regular,
            'bold': self.font_bold,
            'ok': self.fonts_ok,
            'source': self.font_source,
            'error': self.font_error
        }


class Stage1Processor:
    """Обработка Stage 1: сегментация и детекция талька"""

    @staticmethod
    def load_image_bgr_from_bytes(file_bytes, max_side=1600):
        """Загрузка изображения из байтов"""
        arr = np.frombuffer(file_bytes, dtype=np.uint8)
        img_bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if img_bgr is None:
            raise ValueError('Не удалось прочитать изображение')
        h, w = img_bgr.shape[:2]
        scale = min(1.0, max_side / max(h, w))
        if scale < 1.0:
            img_bgr = cv2.resize(img_bgr, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        return img_bgr

    @staticmethod
    def detect_blue_annotation(img_bgr):
        """Детекция синей аннотации"""
        b, g, r = cv2.split(img_bgr)
        return ((b.astype(int) - r.astype(int) > 60) &
                (b.astype(int) - g.astype(int) > 60) &
                (b > 150))

    @staticmethod
    def kmeans_zone_masks(img_bgr, blue_mask, k=4, scale=0.5):
        """Сегментация изображения методом KMeans"""
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
        order = np.argsort(km.cluster_centers_[:, 0])[::-1]

        sulfide_mask = np.isin(full_labels, [order[0], order[1]]) & (~blue_mask)
        background_mask = (full_labels == order[2]) & (~blue_mask)
        potential_talc_mask = (full_labels == order[3]) & (~blue_mask)

        return sulfide_mask, potential_talc_mask, background_mask

    @staticmethod
    def erode_mask(mask, radius):
        """Эрозия маски"""
        if radius <= 0:
            return mask
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (radius * 2 + 1, radius * 2 + 1))
        eroded = cv2.erode(mask.astype(np.uint8), kernel, iterations=1)
        return eroded.astype(bool)

    @staticmethod
    def local_std_map_masked(img_bgr, mask, win=9):
        """Карта локального стандартного отклонения"""
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

    @staticmethod
    def auto_std_threshold(std_map_masked, mask):
        """Автоматический порог по Otsu"""
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

    @staticmethod
    def extract_inclusion_candidates_auto(std_map_masked, mask, open_radius=2):
        """Извлечение кандидатов включений"""
        thr = Stage1Processor.auto_std_threshold(std_map_masked, mask)
        candidate_mask = (std_map_masked > thr) & mask

        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (open_radius * 2 + 1, open_radius * 2 + 1))
        cleaned = cv2.morphologyEx(candidate_mask.astype(np.uint8), cv2.MORPH_OPEN, kernel)

        return cleaned.astype(bool), thr

    @staticmethod
    def auto_sigma_by_contrast(candidate_mask, sigma_grid=(5, 8, 10, 15, 20, 25, 35, 50, 70, 100)):
        """Автоматический подбор параметра sigma"""
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

    @staticmethod
    def candidates_to_density_zone(candidate_mask, sigma=0):
        """Преобразование кандидатов в density-зону"""
        density = cv2.GaussianBlur(candidate_mask.astype(np.float32), (0, 0), sigmaX=sigma)
        return density

    @staticmethod
    def density_to_mask(density, candidate_mask, percentile=50, min_area=15):
        """Преобразование density в маску"""
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

    @classmethod
    def run_stage1(cls, img_bgr, sample_name, radius, density_percentile,
                   density_min_area, stage1_threshold):
        """Запуск полного пайплайна Stage 1"""
        blue_mask = cls.detect_blue_annotation(img_bgr)

        sulfide_mask, potential_talc_mask, background_mask = cls.kmeans_zone_masks(img_bgr, blue_mask, k=4)

        talc_core_mask = cls.erode_mask(potential_talc_mask, radius=radius)
        std_map_masked = cls.local_std_map_masked(img_bgr, talc_core_mask, win=9)

        vals = std_map_masked[talc_core_mask]
        if vals.size == 0:
            raise ValueError(f'[{sample_name}] Пустая зона талька — нечего анализировать')

        inclusion_mask, thr = cls.extract_inclusion_candidates_auto(std_map_masked, talc_core_mask, open_radius=2)
        best_sigma, sigmas, scores = cls.auto_sigma_by_contrast(inclusion_mask)
        density = cls.candidates_to_density_zone(inclusion_mask, sigma=best_sigma)
        zone_mask = cls.density_to_mask(density, inclusion_mask, percentile=density_percentile,
                                        min_area=density_min_area)

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


class CNN(nn.Module):
    """Модель CNN для Stage 2"""

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


class Stage2Classifier:
    """Классификация Stage 2: CNN (рядовая / трудная)"""

    def __init__(self, model_path=None, config=None):
        self.config = config or DEFAULT_CONFIG
        self.model_path = model_path or os.path.join(
            os.path.dirname(os.path.abspath(__file__)),
            'model',
            'cnn_two_class_final.pth'
        )
        self.model = None
        self.device = None
        self.device_str = None
        self._initialize_model()

    def _initialize_model(self):
        """Инициализация модели"""
        self.device, self.device_str = DeviceManager.get_device()
        self.model = CNN(num_classes=len(CLASS_NAMES), img_size=self.config['IMG_SIZE']).to(self.device)

        if os.path.exists(self.model_path):
            state_dict = torch.load(self.model_path, map_location=self.device, weights_only=False)
            self.model.load_state_dict(state_dict)
            self.model.eval()
        else:
            raise FileNotFoundError(f'Модель не найдена: {self.model_path}')

    @staticmethod
    def resize_with_padding(img, target_size=512):
        """Изменение размера с padding"""
        img = img.copy()
        img.thumbnail((target_size, target_size), Image.LANCZOS)
        new_img = Image.new('RGB', (target_size, target_size), (0, 0, 0))
        paste_x = (target_size - img.width) // 2
        paste_y = (target_size - img.height) // 2
        new_img.paste(img, (paste_x, paste_y))
        return new_img

    def run_stage2(self, pil_img, stage2_threshold=None):
        """Запуск классификации Stage 2"""
        if stage2_threshold is None:
            stage2_threshold = self.config['STAGE2_THRESHOLD']

        img_size = self.config['IMG_SIZE']

        # Трансформации
        transform = transforms.Compose([
            transforms.ToTensor(),
            transforms.Normalize((0.5, 0.5, 0.5), (0.5, 0.5, 0.5)),
        ])

        img = self.resize_with_padding(pil_img, target_size=img_size)
        img_tensor = transform(img).unsqueeze(0).to(self.device)

        with torch.no_grad():
            probs = F.softmax(self.model(img_tensor), dim=1)

        prob_trudnie = probs[0, POSITIVE_CLASS_IDX].item()
        pred_idx = int(prob_trudnie > stage2_threshold)
        pred_class = CLASS_NAMES[pred_idx]

        return pred_class, prob_trudnie


class Visualization:
    """Визуализация результатов"""

    @staticmethod
    def fig_zones(stage1_result):
        """Визуализация зон сульфидов и талька"""
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

    @staticmethod
    def fig_density(stage1_result):
        """Визуализация density-карты и скоплений талька"""
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

    @staticmethod
    def fig_to_png_bytes(fig, dpi=150):
        """Конвертация matplotlib фигуры в PNG байты"""
        buf = io.BytesIO()
        fig.savefig(buf, format='png', dpi=dpi, bbox_inches='tight')
        buf.seek(0)
        return buf.getvalue()


class ReportGenerator:
    """Генерация PDF-отчетов"""

    def __init__(self):
        self.font_manager = FontManager()
        self.fonts = self.font_manager.get_fonts()

    def build_pdf_report(self, result, original_png_bytes, zones_png_bytes, density_png_bytes, config=None):
        """Построение PDF-отчета"""
        if config is None:
            config = DEFAULT_CONFIG

        buf = io.BytesIO()
        doc = SimpleDocTemplate(
            buf, pagesize=A4,
            leftMargin=1.5 * cm, rightMargin=1.5 * cm,
            topMargin=1.5 * cm, bottomMargin=1.5 * cm,
        )

        styles = getSampleStyleSheet()
        title_style = ParagraphStyle('TitleRu', parent=styles['Title'],
                                     fontName=self.fonts['bold'], fontSize=18)
        h2_style = ParagraphStyle('H2Ru', parent=styles['Heading2'],
                                  fontName=self.fonts['bold'], spaceBefore=10)
        normal_style = ParagraphStyle('NormalRu', parent=styles['Normal'],
                                      fontName=self.fonts['regular'])

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
            ['STAGE1_THRESHOLD (%)', f"{config['STAGE1_THRESHOLD']:.3f}"],
            ['stage2_prob_trudnie', stage2_prob_str],
            ['STAGE2_THRESHOLD', f"{config['STAGE2_THRESHOLD']:.2f}"],
        ]
        tbl = Table(table_data, colWidths=[8 * cm, 8 * cm])
        tbl.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#2c3e50')),
            ('TEXTCOLOR', (0, 0), (-1, 0), colors.white),
            ('FONTNAME', (0, 0), (-1, 0), self.fonts['bold']),
            ('FONTNAME', (0, 1), (-1, -1), self.fonts['regular']),
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


class OreCascadePipeline:
    """Полный пайплайн каскадной классификации"""

    def __init__(self, config=None, model_path=None):
        self.config = config or DEFAULT_CONFIG.copy()
        self.stage1_processor = Stage1Processor()
        self.stage2_classifier = Stage2Classifier(model_path=model_path, config=self.config)
        self.visualization = Visualization()
        self.report_generator = ReportGenerator()

    def predict_sample(self, file_bytes, sample_name):
        """Предсказание для одного образца"""
        # Stage 1
        img_bgr = Stage1Processor.load_image_bgr_from_bytes(file_bytes)

        stage1_result = Stage1Processor.run_stage1(
            img_bgr, sample_name,
            radius=self.config['RADIUS'],
            density_percentile=self.config['DENSITY_PERCENTILE'],
            density_min_area=self.config['DENSITY_MIN_AREA'],
            stage1_threshold=self.config['STAGE1_THRESHOLD'],
        )

        if stage1_result['stage1_pred'] == 'otalkovanie':
            return {
                'sample': sample_name,
                'final_label': 'otalkovanie',
                'stage1_details': stage1_result,
                'stage2_pred': None,
                'stage2_prob_trudnie': None,
            }

        # Stage 2
        pil_img = Image.open(io.BytesIO(file_bytes)).convert('RGB')
        stage2_pred, stage2_prob = self.stage2_classifier.run_stage2(
            pil_img, self.config['STAGE2_THRESHOLD'])

        return {
            'sample': sample_name,
            'final_label': stage2_pred,
            'stage1_details': stage1_result,
            'stage2_pred': stage2_pred,
            'stage2_prob_trudnie': stage2_prob,
        }

    def process_with_visualization(self, file_bytes, sample_name):
        """Обработка с визуализацией"""
        result = self.predict_sample(file_bytes, sample_name)

        # Генерация визуализаций
        zones_fig = self.visualization.fig_zones(result['stage1_details'])
        density_fig = self.visualization.fig_density(result['stage1_details'])
        zones_png = self.visualization.fig_to_png_bytes(zones_fig)
        density_png = self.visualization.fig_to_png_bytes(density_fig)

        # Генерация PDF отчета
        pdf_bytes = self.report_generator.build_pdf_report(
            result, file_bytes, zones_png, density_png, self.config)

        # JSON результат
        record = {
            'sample': result['sample'],
            'class': result['final_label'],
            'pct_sulfide': result['stage1_details']['pct_sulfide'],
            'pct_inclusions_in_talc': result['stage1_details']['pct_inclusions_in_talc'],
            'pct_final_zone': result['stage1_details']['pct_final_zone'],
            'stage2_prob_trudnie': result['stage2_prob_trudnie'],
        }

        return {
            'result': result,
            'visualizations': {
                'zones_fig': zones_fig,
                'density_fig': density_fig,
                'zones_png': zones_png,
                'density_png': density_png,
            },
            'reports': {
                'pdf_bytes': pdf_bytes,
                'json_data': json.dumps(record, ensure_ascii=False, indent=2),
            }
        }


class BatchProcessor:
    """Пакетная обработка файлов"""

    def __init__(self, pipeline):
        self.pipeline = pipeline

    def process_files(self, file_dict):
        """
        Обработка словаря файлов {имя_файла: байты}

        Args:
            file_dict: dict, где ключи - имена файлов, значения - байты файлов

        Returns:
            pd.DataFrame с результатами
        """
        rows = []

        for file_name, file_bytes in file_dict.items():
            sample_name = os.path.splitext(file_name)[0]
            try:
                result = self.pipeline.predict_sample(file_bytes, sample_name)
                rows.append({
                    'path': file_name,
                    'sample': result['sample'],
                    'final_label': result['final_label'],
                    'pct_inclusions_in_talc': result['stage1_details']['pct_inclusions_in_talc'],
                    'pct_final_zone': result['stage1_details']['pct_final_zone'],
                    'stage2_prob_trudnie': result['stage2_prob_trudnie'],
                })
            except Exception as e:
                rows.append({'path': file_name, 'sample': sample_name, 'error': str(e)})

        return pd.DataFrame(rows)

    def process_directory(self, directory_path):
        """
        Обработка всех изображений в директории

        Args:
            directory_path: путь к директории с изображениями

        Returns:
            pd.DataFrame с результатами
        """
        import glob

        supported_extensions = ['*.jpg', '*.jpeg', '*.png', '*.tif', '*.tiff']
        file_paths = []
        for ext in supported_extensions:
            file_paths.extend(glob.glob(os.path.join(directory_path, ext)))

        file_dict = {}
        for file_path in file_paths:
            with open(file_path, 'rb') as f:
                file_dict[os.path.basename(file_path)] = f.read()

        return self.process_files(file_dict)


# Функция для обратной совместимости
def get_ore_cascade_pipeline(config=None, model_path=None):
    """Фабричная функция для получения пайплайна"""
    return OreCascadePipeline(config=config, model_path=model_path)


if __name__ == '__main__':
    # Пример использования
    pipeline = get_ore_cascade_pipeline()
    print(f"Pipeline инициализирован с конфигурацией: {pipeline.config}")
    print(f"Устройство Stage 2: {pipeline.stage2_classifier.device_str}")
