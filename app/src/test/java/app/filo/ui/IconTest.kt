package app.filo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The launcher icon loads and renders; also saves a preview picture of it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "xxxhdpi")
class IconTest {
    @Test fun launcherIconRenders() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val iconRes = context.applicationInfo.icon
        assertTrue("manifest declares an icon", iconRes != 0)
        val icon = ContextCompat.getDrawable(context, iconRes)
        assertNotNull(icon)
        val size = 432
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon!!.setBounds(0, 0, size, size)
        icon.draw(Canvas(bitmap))
        File("build/screenshots").mkdirs()
        File("build/screenshots/00_icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
