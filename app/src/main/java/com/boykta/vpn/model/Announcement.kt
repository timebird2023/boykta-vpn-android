package com.boykta.vpn.model

data class Announcement(
    val id: Int,
    val mediaUrls: List<String>,
    val linkUrl: String,
    val mediaType: String, // "image" | "video"
)

data class NotificationData(
    val id: Int,
    val title: String,
    val message: String,
    val createdAt: String,
)
