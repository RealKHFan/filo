package app.filo.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.imageDecoderEnabled
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The thumbnail code path: a plain File handed to the image loader must decode. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ThumbnailTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fileDecodesIntoThumbnail() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(tmp.root, "photo.png")
        Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF1D9E75.toInt()) }
            .let { bmp -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val loader = ImageLoader.Builder(context).allowHardware(false).imageDecoderEnabled(false).build()
        val result = loader.execute(ImageRequest.Builder(context).data(file).size(96).build())
        if (result is ErrorResult) result.throwable.printStackTrace()
        assertTrue("got $result", result is SuccessResult)
        println("THUMB ${(result as SuccessResult).image.width}x${result.image.height}")
    }

    private fun png(width: Int, height: Int): ByteArray = java.io.ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF1D9E75.toInt()) }.compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    @Test fun `hidden and server pictures decode small, wide panoramas too`() {
        val photo = decodeImage(png(4000, 3000), 256)!!
        assertEquals(500 to 375, photo.width to photo.height) // sharp enough for a cropped thumbnail

        val panorama = decodeImage(png(8200, 2100), 2048)!!
        assertTrue("${panorama.width}x${panorama.height}", panorama.width.toLong() * panorama.height <= 16_000_000)
        assertEquals(4100, panorama.width)
    }
}
