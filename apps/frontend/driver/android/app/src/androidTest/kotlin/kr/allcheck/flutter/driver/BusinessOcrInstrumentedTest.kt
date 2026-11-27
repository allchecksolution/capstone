package kr.allcheck.flutter.driver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BusinessOcrInstrumentedTest {
    @Test fun readsKoreanBusinessDocumentImageOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 52f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val lines = listOf("사업자등록증", "등록번호 : 123-45-67890", "상호 : 테스트 설비", "대표자 : 김기사", "업태 : 서비스업", "종목 : 배관 수리")
        for ((index, line) in lines.withIndex()) canvas.drawText(line, 70f, 110f + index * 110f, paint)
        val file = File(context.cacheDir, "synthetic-business-ocr-test.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val recognized = Tasks.await(recognizer.process(InputImage.fromFilePath(context, Uri.fromFile(file))), 60, TimeUnit.SECONDS)
            val compact = recognized.text.replace(Regex("\\s"), "")
            assertTrue("Korean document heading", compact.contains("사업자등록증"))
            assertTrue("Registration number", compact.contains("123-45-67890"))
            assertTrue("Korean company name", compact.contains("테스트설비"))
        } finally {
            recognizer.close()
            file.delete()
        }
    }
}
