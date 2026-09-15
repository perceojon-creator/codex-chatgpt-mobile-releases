package com.codex.chat.core.media

import android.graphics.Color
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter ligero para el carrusel de imágenes ViewPager2.
 * Permite deslizar lateralmente entre múltiples imágenes generadas.
 * Cada página usa MediaBitmapCache para decodificación asíncrona fuera del hilo principal.
 */
class ImageCarouselAdapter(
    private var images: List<String> = emptyList(),
    private val onImageClick: (position: Int, source: String) -> Unit
) : RecyclerView.Adapter<ImageCarouselAdapter.ImageViewHolder>() {

    class ImageViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

    fun submitImages(newImages: List<String>) {
        images = newImages
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = images.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val iv = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#12161F"))
        }
        return ImageViewHolder(iv)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val source = images[position]
        val cacheKey = MediaBitmapCache.keyFor(source)
        val cached = MediaBitmapCache.get(cacheKey)
        if (cached != null) {
            holder.imageView.setImageBitmap(cached)
        } else {
            holder.imageView.setImageDrawable(null)
            MediaBitmapCache.decodeAsync(cacheKey, source) { bmp ->
                if (holder.adapterPosition == position && bmp != null) {
                    holder.imageView.setImageBitmap(bmp)
                }
            }
        }

        holder.imageView.setOnClickListener {
            onImageClick(position, source)
        }
    }
}
