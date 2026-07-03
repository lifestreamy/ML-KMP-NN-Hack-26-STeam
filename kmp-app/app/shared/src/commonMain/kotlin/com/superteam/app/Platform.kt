package com.superteam.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform