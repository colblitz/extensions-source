package eu.kanade.tachiyomi.extension.all.xkcd

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

// Designer values (same as TextInterceptor)
private const val WIDTH: Int = 1000
private const val X_PADDING: Float = 50f
private const val Y_PADDING: Float = 25f
private const val HEADING_FONT_SIZE: Float = 36f
private const val BODY_FONT_SIZE: Float = 30f
private const val SPACING_MULT: Float = 1.1f
private const val SPACING_ADD: Float = 2f
private const val HOST = "tachiyomi-lib-textinterceptor"

/**
 * Custom text interceptor for xkcd that bundles NotoSansSymbols font
 * to support Mathematical Alphanumeric Symbols (e.g., cursive letters in comic #2912).
 * 
 * This is based on the shared TextInterceptor but includes a bundled font
 * for better Unicode support across all Android versions.
 */
class XkcdTextInterceptor : Interceptor {
    
    // Lazy-load the symbol font to support Mathematical Alphanumeric Symbols
    private val symbolFont: Typeface? by lazy {
        loadFont("NotoSansSymbols-Regular-Subsetted.ttf")
    }
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != HOST) return chain.proceed(request)

        val heading = url.pathSegments[0].takeIf { it.isNotEmpty() }?.let {
            val title = textFixer(url.pathSegments[0])

            val paintHeading = TextPaint().apply {
                color = Color.BLACK
                textSize = HEADING_FONT_SIZE
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }

            @Suppress("DEPRECATION")
            StaticLayout(
                title, paintHeading, (WIDTH - 2 * X_PADDING).toInt(),
                Layout.Alignment.ALIGN_NORMAL, SPACING_MULT, SPACING_ADD, true
            )
        }

        val body = url.pathSegments[1].takeIf { it.isNotEmpty() }?.let {
            val story = textFixer(it)

            val paintBody = TextPaint().apply {
                color = Color.BLACK
                textSize = BODY_FONT_SIZE
                // Use bundled symbol font if available, fallback to default
                typeface = symbolFont ?: Typeface.DEFAULT
                isAntiAlias = true
            }

            @Suppress("DEPRECATION")
            StaticLayout(
                story, paintBody, (WIDTH - 2 * X_PADDING).toInt(),
                Layout.Alignment.ALIGN_NORMAL, SPACING_MULT, SPACING_ADD, true
            )
        }

        // Image building
        val headingHeight = heading?.height ?: 0
        val bodyHeight = body?.height ?: 0
        val imgHeight: Int = (headingHeight + bodyHeight + 2 * Y_PADDING).toInt()
        val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, imgHeight, Bitmap.Config.ARGB_8888)

        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            heading?.draw(this, X_PADDING, Y_PADDING)
            body?.draw(this, X_PADDING, Y_PADDING + headingHeight.toFloat())
        }

        // Image converting & returning
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 0, stream)
        val responseBody = stream.toByteArray().toResponseBody("image/png".toMediaType())
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody)
            .build()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun textFixer(htmlString: String): String {
        return if (Build.VERSION.SDK_INT >= 24) {
            Html.fromHtml(htmlString, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(htmlString).toString()
        }
    }

    /**
     * Loads font from the `assets/fonts` directory within the APK.
     * 
     * @param fontName The name of the font to load.
     * @return A `Typeface` instance of the loaded font or `null` if an error occurs.
     */
    private fun loadFont(fontName: String): Typeface? {
        return try {
            this::class.java.classLoader!!
                .getResourceAsStream("assets/fonts/$fontName")
                ?.toTypeface(fontName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converts an InputStream to a Typeface by creating a temporary file.
     */
    private fun InputStream.toTypeface(fontName: String): Typeface? {
        return try {
            val fontFile = File.createTempFile(fontName, fontName.substringAfter("."))
            this.copyTo(FileOutputStream(fontFile))
            Typeface.createFromFile(fontFile)
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("SameParameterValue")
    private fun StaticLayout.draw(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        this.draw(canvas)
        canvas.restore()
    }
}
