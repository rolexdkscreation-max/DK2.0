package com.dk.assistant.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.dk.assistant.Mode

/** सिस्टम में animation बंद (Developer options / Accessibility "Remove animations") हो तो true */
object Motion {
    fun reduced(c: Context): Boolean = try {
        !android.animation.ValueAnimator.areAnimatorsEnabled() ||
            Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) { false }

    /** यह फ़ोन shader वाला गोला बना सकता है? (Android 13+) */
    val shaderOk: Boolean get() = Build.VERSION.SDK_INT >= 33
}

/**
 * v3.57 (Part 10): असली रोशनी वाला 3D गोला - AGSL RuntimeShader (Android 13+)।
 * गोले की सतह की normal से रोशनी (diffuse + चमक), अंदर धुएँ जैसे बादल (fbm),
 * किनारे पर fresnel चमक और बाहर halo। फ़ोन झुकाओ तो रोशनी साथ घूमती है (accelerometer, low-pass)।
 * पुराने फ़ोन पर OrbView पुराना डॉट वाला ओर्ब ही दिखाता है।
 */
@RequiresApi(33)
class Sphere3D(private val ctx: Context) : SensorEventListener {
    private val shader = RuntimeShader(SRC)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@Sphere3D.shader }
    private var core = 0; private var glow = 0; private var rim = 0
    private var tiltX = 0f; private var tiltY = 0f
    private var sensorOn = false
    private var amp = 0f
    private var reducedCheckT = 0L
    private var reduced = false
    private var frozenT = 0f

    fun start() {
        if (sensorOn) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorOn = sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!sensorOn) return
        (ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(this)
        sensorOn = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        // झुकाव -> रोशनी की दिशा (बस हल्का सा, ±0.35)
        val tx = (-e.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) * 0.35f
        val ty = (e.values[1] / SensorManager.GRAVITY_EARTH - 0.6f).coerceIn(-1f, 1f) * 0.35f
        tiltX = OrbLook.lowPass(tiltX, tx); tiltY = OrbLook.lowPass(tiltY, ty)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** हर frame: dt सेकंड, t = समय */
    fun draw(c: Canvas, w: Float, h: Float, mode: Mode, level: Float, t: Float, dt: Float) {
        val now = System.currentTimeMillis()
        if (now - reducedCheckT > 2000) { reducedCheckT = now; reduced = Motion.reduced(ctx) }
        val tt = if (reduced) frozenT else t.also { frozenT = it }
        val p = OrbLook.palette(mode)
        val k = (1f - kotlin.math.exp(-dt * 8f)).coerceIn(0.05f, 1f)
        if (core == 0) { core = p.core; glow = p.glow; rim = p.rim }
        core = OrbLook.lerp(core, p.core, k); glow = OrbLook.lerp(glow, p.glow, k); rim = OrbLook.lerp(rim, p.rim, k)
        amp += (OrbLook.amplitude(mode, level, tt) - amp) * 0.25f
        val radius = minOf(w, h) * 0.30f * OrbLook.scale(mode, tt, reduced)
        shader.setFloatUniform("res", w, h)
        shader.setFloatUniform("time", tt)
        shader.setFloatUniform("amp", if (reduced) 0f else amp)
        shader.setFloatUniform("swirl", OrbLook.swirl(mode))
        shader.setFloatUniform("tilt", tiltX, tiltY)
        shader.setFloatUniform("radius", radius)
        color("core", core); color("glow", glow); color("rim", rim)
        paint.shader = shader
        c.drawRect(0f, 0f, w, h, paint)
    }

    private fun color(name: String, v: Int) {
        shader.setFloatUniform(name, ((v shr 16) and 0xFF) / 255f, ((v shr 8) and 0xFF) / 255f, (v and 0xFF) / 255f, 1f)
    }

    companion object {
        val SRC = """
            uniform float2 res;
            uniform float time;
            uniform float amp;
            uniform float swirl;
            uniform float2 tilt;
            uniform float radius;
            uniform float4 core;
            uniform float4 glow;
            uniform float4 rim;

            float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
            float noise(float2 p) {
                float2 i = floor(p); float2 f = fract(p);
                float2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
                           mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
            }
            float fbm(float2 p) {
                float v = 0.0; float a = 0.5;
                for (int i = 0; i < 4; i++) { v += a * noise(p); p = p * 2.03 + float2(1.7, 9.2); a *= 0.5; }
                return v;
            }
            half4 main(float2 fc) {
                float2 q = (fc - res * 0.5) / radius;
                float wob = 1.0 + amp * 0.06 * sin(atan(q.y, q.x) * 6.0 + time * 4.0);
                float r = length(q) / wob;
                if (r > 1.0) {
                    float hl = exp(-(r - 1.0) * 3.5) * (0.30 + 0.45 * amp);
                    return half4(glow.rgb * hl, hl);
                }
                float z = sqrt(max(0.0, 1.0 - r * r));
                float3 n = normalize(float3(q / wob, z));
                float3 L = normalize(float3(-0.45 + tilt.x, -0.55 + tilt.y, 0.8));
                float diff = max(dot(n, L), 0.0);
                float3 H = normalize(L + float3(0.0, 0.0, 1.0));
                float spec = pow(max(dot(n, H), 0.0), 48.0);
                float2 uv = n.xy * 1.6 + float2(time * swirl * 0.30, -time * swirl * 0.20);
                float f = fbm(uv + fbm(uv * 1.3 + time * swirl * 0.15));
                float3 col = mix(core.rgb, glow.rgb, smoothstep(0.25, 0.85, f));
                col *= 0.35 + 0.75 * diff;
                float fres = pow(1.0 - z, 3.0);
                col += rim.rgb * fres * 0.9 + float3(spec * 0.85);
                float edge = smoothstep(1.0, 0.96, r);
                float a = edge + (1.0 - edge) * 0.4;
                return half4(col * edge + glow.rgb * (1.0 - edge) * 0.4, a);
            }
        """.trimIndent()
    }
}
