package io.legado.app.epd

import android.graphics.Bitmap
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * 图片灰阶量化（Glide）。
 *
 * 用在封面上：封面是列表里面积最大、灰度最连续的块，
 * 交给面板自己抖动会显得脏；预先量化成确定网点反而更像印刷品。
 */
class EinkGrayTransformation(
    private val levels: Int = EinkGray.LEVELS_4
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap = EinkGray.quantize(toTransform, levels)

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("eink gray transformation v1 levels=$levels".toByteArray())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EinkGrayTransformation) return false
        return other.levels == levels
    }

    override fun hashCode(): Int = "eink-gray-$levels".hashCode()
}
