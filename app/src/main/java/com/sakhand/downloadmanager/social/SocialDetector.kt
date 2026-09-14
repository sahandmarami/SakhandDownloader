package com.sakhand.downloadmanager.social

import com.sakhand.downloadmanager.data.SocialPlatform

/**
 * تشخیص خودکار پلتفرم از روی لینک
 */
object SocialDetector {

    fun detect(url: String): SocialPlatform? {
        val u = url.trim().lowercase()
        return when {
            "youtube.com" in u || "youtu.be" in u || "youtube-nocookie.com" in u -> SocialPlatform.YOUTUBE
            "instagram.com" in u || "instagr.am" in u -> SocialPlatform.INSTAGRAM
            "pinterest.com" in u || "pin.it" in u -> SocialPlatform.PINTEREST
            else -> null
        }
    }
}
