package ru.argeda.tv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Random
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Заставка при запуске под звук res/raw/argeda_tv.mp3 (мощный удар + басовый голос «ARGEDA TV»).
 *
 * Сцены (мс): 0–[HIT] — «зарядка»: искры по спирали стягиваются в светящееся ядро, из него
 * появляется круглый знак, вихрь закручивается к центру, экран с Play прилетает и «прилипает». [HIT] — удар: вихрь раскрывается, вспышка, встряска,
 * ударные кольца, взрыв искр, лучи. Затем надпись по буквам и голос, блик, вокруг знака кружат огоньки.
 * HOLD — финал: пролёт сквозь знак (он стремительно растёт и растворяется).
 *
 * Лежит поверх главного экрана: плейлист и первый канал грузятся одновременно с ней.
 * Любая кнопка или касание её пропускают.
 */
internal class Splash private constructor(private val a: Activity, private val done: Done) {
    fun interface Done {
        fun run()
    }

    private var root: FrameLayout? = null
    private lateinit var col: LinearLayout
    private var fx: Fx? = null
    private var voice: MediaPlayer? = null
    private var closing = false
    private val auto = Runnable { close(true) }

    fun showing(): Boolean = root != null && !closing

    private fun dp(v: Float): Int = (v * a.resources.displayMetrics.density).roundToInt()

    private fun build() {
        val root = FrameLayout(a)
        this.root = root
        root.setBackgroundColor(0xFF07050F.toInt())
        root.isClickable = true
        root.setOnClickListener { skip() }
        root.isFocusable = false // касание пропускает заставку, но фокус пульта остаётся на списке

        val size = dp(150f)
        val fx = Fx(a, size)
        this.fx = fx
        root.addView(fx, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL

        // Круглый знак, как в шапке приложения: вихрь из трёх цветов и экран с Play поверх.
        // Всё внутри обрезается по кругу, чтобы блик не вылезал.
        val logo = FrameLayout(a)
        logo.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) {
                o.setOval(0, 0, view.width, view.height)
            }
        }
        logo.clipToOutline = true
        logo.elevation = dp(16f).toFloat()
        val ring = ImageView(a)
        ring.setImageResource(R.drawable.splash_ring)
        val screen = ImageView(a)
        screen.setImageResource(R.drawable.splash_play)
        val shine = View(a)
        shine.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF)
        )
        logo.addView(ring, FrameLayout.LayoutParams(size, size))
        logo.addView(screen, FrameLayout.LayoutParams(size, size))
        logo.addView(shine, FrameLayout.LayoutParams(size / 3, size * 2, Gravity.CENTER_VERTICAL))
        col.addView(logo, LinearLayout.LayoutParams(size, size))
        fx.anchor = logo

        // Надпись по буквам: ARGEDA белым, TV фирменным фиолетовым.
        val title = LinearLayout(a)
        title.orientation = LinearLayout.HORIZONTAL
        val text = "ARGEDA TV"
        val letters = arrayOfNulls<TextView>(text.length)
        for (i in text.indices) {
            val l = TextView(a)
            l.text = text[i].toString()
            l.setTextColor(if (i >= 7) 0xFFB57BFF.toInt() else Color.WHITE)
            l.textSize = 36f
            l.typeface = Typeface.DEFAULT_BOLD
            l.includeFontPadding = false
            l.setShadowLayer(dp(10f).toFloat(), 0f, 0f, if (i >= 7) 0xCC8B5CF6.toInt() else 0x667C3AED)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(1.5f)
            lp.rightMargin = lp.leftMargin
            title.addView(l, lp)
            letters[i] = l
        }
        val tl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tl.topMargin = dp(26f)
        col.addView(title, tl)

        val line = View(a)
        line.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00A855F7, 0xFFA855F7.toInt(), 0xFFFFFFFF.toInt(), 0xFFA855F7.toInt(), 0x00A855F7)
        )
        val ll = LinearLayout.LayoutParams(dp(220f), dp(2f))
        ll.topMargin = dp(12f)
        col.addView(line, ll)

        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        a.addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Знак рождается из ядра в конце «зарядки».
        val born = HIT - 420
        logo.scaleX = 0f
        logo.scaleY = 0f
        logo.rotation = -45f
        logo.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(born).setDuration(520)
            .setInterpolator(OvershootInterpolator(1.5f))
        // Вихрь: знак рождается раскрытым, вихрь закручивается и сжимается к центру,
        // а на удар раскручивается обратно и раскрывается на всю ширину.
        ring.rotation = -120f
        ring.animate().rotation(-300f).scaleX(0.5f).scaleY(0.5f).setStartDelay(born + 80).setDuration(300)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .withEndAction {
                if (closing) return@withEndAction
                val wait = max(0L, (HIT - 40) - (born + 80 + 300))
                // Без перелёта по углу: иначе из-под экрана выглянули бы углы белой подложки.
                ring.animate().rotation(0f).scaleX(1f).scaleY(1f).setStartDelay(wait).setDuration(620)
                    .setInterpolator(DecelerateInterpolator(2.2f))
            }
        // Экран с Play прилетает из глубины прямо на сжатый вихрь и «прилипает»:
        // сплющивается от удара и пружинит на место.
        screen.scaleX = 2.8f
        screen.scaleY = 2.8f
        screen.alpha = 0f
        screen.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(HIT - 220).setDuration(200)
            .setInterpolator(AccelerateInterpolator(1.8f))
            .withEndAction {
                screen.animate().scaleX(1.25f).scaleY(0.8f).setStartDelay(0).setDuration(90)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        screen.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(420)
                            .setInterpolator(OvershootInterpolator(3f))
                    }
            }
        // Удар: знак «вздыхает», вся сцена вздрагивает.
        root.postDelayed({
            if (closing) return@postDelayed
            logo.animate().scaleX(1.12f).scaleY(1.12f).setStartDelay(0).setDuration(110).setInterpolator(DecelerateInterpolator())
                .withEndAction { logo.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(2f)) }
            shake(col, dp(7f).toFloat())
        }, HIT)
        // Буквы по одной вместе с голосом.
        for (i in letters.indices) {
            val l = letters[i]!!
            l.alpha = 0f
            l.translationY = dp(24f).toFloat()
            l.scaleX = 1.8f
            l.scaleY = 1.8f
            l.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(HIT + 260 + i * 60L).setDuration(400)
                .setInterpolator(OvershootInterpolator(1.8f))
        }
        // Линия под надписью растёт от центра.
        line.scaleX = 0f
        line.alpha = 0f
        line.animate().scaleX(1f).alpha(1f).setStartDelay(HIT + 900).setDuration(600).setInterpolator(DecelerateInterpolator(2f))
        // Два блика по знаку.
        shine.rotation = 20f
        shine.translationX = -size * 0.6f
        shine.animate().translationX(size * 1.3f).setStartDelay(HIT + 650).setDuration(650).setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                shine.translationX = -size * 0.6f
                shine.animate().translationX(size * 1.3f).setStartDelay(900).setDuration(650)
                    .setInterpolator(AccelerateDecelerateInterpolator()).withEndAction(null)
            }
        // Пока звучит голос, знак медленно «парит».
        col.animate().translationY(-dp(6f).toFloat()).setStartDelay(HIT + 900).setDuration(HOLD - HIT - 900)
            .setInterpolator(AccelerateDecelerateInterpolator())

        fx.start(HOLD + WARP)
        speak()
        root.postDelayed(auto, HOLD)
    }

    private fun speak() {
        try {
            val m = MediaPlayer()
            m.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            val fd = a.resources.openRawResourceFd(R.raw.argeda_tv)
            m.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            m.setOnPreparedListener { it.start() }
            m.setOnCompletionListener { releaseVoice() }
            m.setOnErrorListener { _, _, _ ->
                releaseVoice()
                true
            }
            voice = m
            m.prepareAsync()
        } catch (_: Exception) {
            releaseVoice()
        }
    }

    private fun releaseVoice() {
        voice?.release()
        voice = null
    }

    /** Плавно приглушает звук заставки и освобождает плеер (без щелчка и обрыва). */
    private fun fadeVoice(ms: Long) {
        val m = voice ?: return
        voice = null
        val va = ValueAnimator.ofFloat(1f, 0f).setDuration(ms)
        va.addUpdateListener { an ->
            val v = an.animatedValue as Float
            try {
                m.setVolume(v * v, v * v)
            } catch (_: Exception) {
            }
        }
        va.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: Animator) {
                m.release()
            }
        })
        va.start()
    }

    /** Пропуск кнопкой или касанием: звук быстро затихает, заставка гаснет. */
    fun skip() {
        fadeVoice(400)
        close(false)
    }

    fun dismiss() {
        close(true)
    }

    /** warp: финальный пролёт сквозь знак; иначе — просто быстрое затухание. */
    private fun close(warp: Boolean) {
        val root = root
        if (root == null || closing) return
        closing = true
        root.removeCallbacks(auto)
        root.isClickable = false
        if (warp) {
            fx?.warp = true
            col.animate().cancel()
            col.animate().scaleX(5f).scaleY(5f).alpha(0f).setStartDelay(0).setDuration(WARP)
                .setInterpolator(AccelerateInterpolator(2f))
            root.animate().alpha(0f).setStartDelay(WARP / 2).setDuration(WARP / 2).withEndAction { remove() }
        } else {
            root.animate().alpha(0f).setStartDelay(0).setDuration(SKIP).withEndAction { remove() }
        }
        done.run()
    }

    /** Сразу убирает заставку и звук (уход с экрана). */
    fun cancel() {
        releaseVoice()
        val root = root ?: return
        root.removeCallbacks(auto)
        root.animate().cancel()
        if (!closing) {
            closing = true
            done.run()
        }
        remove()
    }

    private fun remove() {
        val root = root ?: return
        fx?.stop()
        val p = root.parent as ViewGroup?
        if (p != null) {
            p.removeView(root)
            // Некоторые ТВ-приставки после снятия полноэкранного слоя перерисовывают не весь экран.
            val top = p.rootView
            top.invalidate()
            top.requestLayout()
        }
        this.root = null
    }

    /** Эффекты на фоне; всё считается от времени t (мс), поэтому кадры не накапливают ошибок. */
    private class Fx(c: Context, logoPx: Int) : View(c) {
        private val logo: Float = logoPx.toFloat()
        private val dens: Float = c.resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ray = Path()
        private val inAng = FloatArray(N_IN)
        private val inDist = FloatArray(N_IN)
        private val inDelay = FloatArray(N_IN)
        private val inSz = FloatArray(N_IN)
        private val inCol = IntArray(N_IN)
        private val outAng = FloatArray(N_OUT)
        private val outSpd = FloatArray(N_OUT)
        private val outSz = FloatArray(N_OUT)
        private val outCol = IntArray(N_OUT)
        private val orbR = FloatArray(N_ORBIT)
        private val orbPh = FloatArray(N_ORBIT)
        private val orbSpd = FloatArray(N_ORBIT)
        private val starX = FloatArray(N_STAR)
        private val starY = FloatArray(N_STAR)
        private val starPh = FloatArray(N_STAR)
        private var va: ValueAnimator? = null
        private var t = 0L
        private var warpAt = -1L
        var warp = false
        var anchor: View? = null

        init {
            val r = Random(7)
            for (i in 0 until N_IN) {
                inAng[i] = (r.nextFloat() * Math.PI * 2).toFloat()
                inDist[i] = 0.55f + r.nextFloat() * 0.6f
                inDelay[i] = r.nextFloat() * 0.35f
                inSz[i] = 1.2f + r.nextFloat() * 2.2f
                inCol[i] = SPARK[r.nextInt(SPARK.size)]
            }
            for (i in 0 until N_OUT) {
                outAng[i] = (r.nextFloat() * Math.PI * 2).toFloat()
                outSpd[i] = 0.35f + r.nextFloat() * 0.8f
                outSz[i] = 1.4f + r.nextFloat() * 2.8f
                outCol[i] = SPARK[r.nextInt(SPARK.size)]
            }
            for (i in 0 until N_ORBIT) {
                orbR[i] = 0.75f + r.nextFloat() * 0.55f
                orbPh[i] = (r.nextFloat() * Math.PI * 2).toFloat()
                orbSpd[i] = (0.6f + r.nextFloat() * 0.8f) * (if (r.nextBoolean()) 1 else -1)
            }
            for (i in 0 until N_STAR) {
                starX[i] = r.nextFloat()
                starY[i] = r.nextFloat()
                starPh[i] = (r.nextFloat() * Math.PI * 2).toFloat()
            }
        }

        fun start(dur: Long) {
            val v = ValueAnimator.ofFloat(0f, 1f)
            v.duration = dur
            v.interpolator = LinearInterpolator()
            v.addUpdateListener { an ->
                t = an.currentPlayTime
                if (warp && warpAt < 0) warpAt = t
                invalidate()
            }
            v.start()
            va = v
        }

        fun stop() {
            va?.cancel()
        }

        private fun grad(cx: Float, cy: Float, rad: Float, c0: Int) {
            p.shader = RadialGradient(cx, cy, max(rad, 1f), intArrayOf(c0, c0 and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            var cx = w / 2f
            var cy = h / 2f - 41 * dens
            val anc = anchor
            if (anc != null && anc.width > 0) {
                val parent = anc.parent as View
                cx = parent.left + anc.left + anc.width / 2f
                cy = parent.top + anc.top + anc.height / 2f
            }
            val big = max(w, h)
            val hit = if (t < HIT) 0f else exp(-(t - HIT) / 280.0).toFloat()
            val after = clamp((t - HIT) / 600f)
            val charge = clamp(t / HIT.toFloat())

            // 1. Звёздная пыль: мерцает, во время зарядки её «засасывает» к центру.
            for (i in 0 until N_STAR) {
                val pull = if (t < HIT) charge * charge * 0.25f else 0f
                var x = starX[i] * w
                var y = starY[i] * h
                x += (cx - x) * pull
                y += (cy - y) * pull
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t / 220.0 + starPh[i]).toFloat())
                p.shader = null
                p.color = Color.argb((120 * tw * clamp(t / 400f)).toInt(), 200, 190, 255)
                c.drawCircle(x, y, 1.1f * dens, p)
            }

            // 2. Фиолетовое свечение: растёт с зарядкой, вспыхивает на ударе, потом дышит.
            val breathe = 0.85f + 0.15f * sin(t / 380.0).toFloat()
            val glow = if (t < HIT) 0.25f * charge else 0.55f * breathe
            val a1 = min(255, (255 * (glow + 0.5f * hit)).toInt())
            p.shader = RadialGradient(
                cx, cy, big * 0.65f,
                intArrayOf(Color.argb(a1, 80, 42, 170), Color.argb(a1 / 3, 40, 20, 90), 0x00000000),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, p)

            // 3. Лучи за плиткой после удара.
            if (after > 0) {
                p.shader = RadialGradient(
                    cx, cy, big * 0.55f,
                    intArrayOf(Color.argb((80 * after).toInt(), 200, 170, 255), 0x00000000), null, Shader.TileMode.CLAMP
                )
                c.save()
                c.rotate(t / 36f, cx, cy)
                ray.reset()
                for (i in 0 until 14) {
                    val a0 = i * Math.PI / 7
                    val da = Math.PI / 34
                    ray.moveTo(cx, cy)
                    ray.lineTo(cx + cos(a0 - da).toFloat() * big, cy + sin(a0 - da).toFloat() * big)
                    ray.lineTo(cx + cos(a0 + da).toFloat() * big, cy + sin(a0 + da).toFloat() * big)
                    ray.close()
                }
                c.drawPath(ray, p)
                c.restore()
            }

            // 4. Зарядка: искры по спирали летят в центр, ядро разгорается.
            if (t < HIT + 60) {
                for (i in 0 until N_IN) {
                    val f = clamp((charge - inDelay[i]) / (1 - inDelay[i]))
                    if (f <= 0 || f >= 1) continue
                    val e = f * f * f
                    val d = big * inDist[i] * (1 - e)
                    val an = inAng[i] + e * 3.2
                    val x = cx + cos(an).toFloat() * d
                    val y = cy + sin(an).toFloat() * d
                    val px = cx + cos(an - 0.12).toFloat() * d * 1.08f
                    val py = cy + sin(an - 0.12).toFloat() * d * 1.08f
                    p.shader = null
                    val al = (255 * min(1f, f * 3)).toInt()
                    p.color = (inCol[i] and 0x00FFFFFF) or (al shl 24)
                    p.strokeWidth = inSz[i] * dens
                    p.strokeCap = Paint.Cap.ROUND
                    c.drawLine(px, py, x, y, p)
                }
                val core = charge * charge
                grad(cx, cy, logo * (0.15f + 0.9f * core), Color.argb((255 * core).toInt(), 230, 210, 255))
                c.drawCircle(cx, cy, logo * (0.15f + 0.9f * core), p)
            }

            // 5. Ореол прямо за плиткой.
            val halo = clamp(if (t < HIT) 0.4f * charge else 0.45f * breathe + 0.9f * hit)
            grad(cx, cy, logo * 1.3f, Color.argb((255 * halo).toInt(), 168, 85, 247))
            c.drawCircle(cx, cy, logo * 1.3f, p)
            p.shader = null

            // 6. Ударные кольца.
            p.style = Paint.Style.STROKE
            for (k in 0 until 4) {
                val rt = (t - HIT - k * 130) / 1000f
                if (rt <= 0 || rt >= 1) continue
                val e = 1 - (1 - rt) * (1 - rt)
                p.strokeWidth = (4.5f - 3.5f * rt) * dens
                p.color = if (k % 2 == 0) Color.argb((220 * (1 - rt)).toInt(), 255, 255, 255)
                else Color.argb((200 * (1 - rt)).toInt(), 196, 181, 253)
                c.drawCircle(cx, cy, logo * 0.62f + e * big * 0.5f, p)
            }
            p.style = Paint.Style.FILL

            // 7. Взрыв искр фирменных цветов.
            val st = (t - HIT) / 1500f
            if (st > 0 && st < 1) {
                val e = 1 - (1 - st).toDouble().pow(3.0).toFloat()
                val al = (255 * (1 - st)).toInt()
                for (i in 0 until N_OUT) {
                    val d = logo * 0.55f + e * outSpd[i] * big * 0.5f
                    val x = cx + cos(outAng[i].toDouble()).toFloat() * d
                    val y = cy + sin(outAng[i].toDouble()).toFloat() * d + st * st * 50 * dens
                    p.color = (outCol[i] and 0x00FFFFFF) or (al shl 24)
                    c.drawCircle(x, y, outSz[i] * dens * (1.3f - 0.7f * st), p)
                }
            }

            // 8. Огоньки кружат вокруг знака, пока звучит голос.
            val orb = clamp((t - HIT - 400) / 500f)
            if (orb > 0) {
                for (i in 0 until N_ORBIT) {
                    val an = orbPh[i] + orbSpd[i] * t / 600.0
                    val rr = logo * orbR[i]
                    val x = cx + cos(an).toFloat() * rr
                    val y = cy + sin(an).toFloat() * rr * 0.55f
                    val tw = 0.5f + 0.5f * sin(t / 150.0 + i).toFloat()
                    grad(x, y, 6 * dens, Color.argb((230 * orb * tw).toInt(), 220, 200, 255))
                    c.drawCircle(x, y, 6 * dens, p)
                }
                p.shader = null
            }

            // 9. Вспышка на ударе и белый «пролёт» в финале.
            var flash = (190 * hit * hit).toInt()
            if (warpAt >= 0) flash = max(flash, (230 * clamp((t - warpAt) / WARP.toFloat())).toInt())
            if (flash > 0) {
                p.shader = null
                p.color = Color.argb(min(255, flash), 245, 240, 255)
                c.drawRect(0f, 0f, w, h, p)
            }
        }

        private companion object {
            const val N_IN = 70
            const val N_OUT = 56
            const val N_ORBIT = 18
            const val N_STAR = 90

            fun clamp(v: Float): Float = if (v < 0) 0f else if (v > 1) 1f else v
        }
    }

    companion object {
        /** Момент удара в звуке, мс (совпадает с HIT в tools/sound/mix.py). */
        const val HIT = 1300L
        private const val HOLD = 4100L
        private const val WARP = 550L
        private const val SKIP = 250L
        private val SPARK = intArrayOf(
            0xFFE30613.toInt(), 0xFF0047BB.toInt(), 0xFFF28C00.toInt(), 0xFFA855F7.toInt(), 0xFFC4B5FD.toInt(), Color.WHITE
        )

        @JvmStatic
        fun show(a: Activity, done: Done): Splash {
            val s = Splash(a, done)
            s.build()
            return s
        }

        /** Короткая затухающая встряска. */
        private fun shake(v: View, amp: Float) {
            val s = ValueAnimator.ofFloat(0f, 1f)
            s.duration = 320
            s.addUpdateListener { an ->
                val f = an.animatedFraction
                val k = (1 - f) * amp
                v.translationX = sin(f * 40.0).toFloat() * k
                v.translationY = cos(f * 33.0).toFloat() * k * 0.6f
            }
            s.start()
        }
    }
}
