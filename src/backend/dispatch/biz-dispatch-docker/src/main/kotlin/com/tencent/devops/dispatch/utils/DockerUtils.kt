package com.tencent.devops.dispatch.utils

object DockerUtils {
    fun parseShortImage(image: String): String {
        return if (image.contains("/")) {
            image.substring(image.lastIndexOf("/") + 1)
        } else {
            image
        }
    }
}
