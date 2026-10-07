package com.churchproductionpro.remote

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var ipBox: EditText
    private lateinit var pinBox: EditText
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var current: TextView
    private lateinit var next: TextView
    private lateinit var service: LinearLayout
    private lateinit var sections: LinearLayout

    private var connected = false
    private var master = ""
    private var pin = ""
    private var mirrorSig = ""
    private var serviceSig = ""
    private var sectionSig = ""

    private val prefs by lazy { getSharedPreferences("cpp_remote", MODE_PRIVATE) }
    private val mirrorFile by lazy { File(filesDir, "service_mirror.json") }

    private fun dp(v:Int)= (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ipBox.setText(prefs.getString("ip","") ?: "")
        pinBox.setText(prefs.getString("pin","") ?: "")
        loadMirror()
        ui.post(poller)
    }

    private fun buildUi() {
        val scroll=ScrollView(this)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(12),dp(12),dp(20))
            setBackgroundColor(Color.rgb(9,13,18))
        }
        scroll.addView(root)
        setContentView(scroll)

        fun label(t:String,size:Float=14f,bold:Boolean=false)=TextView(this).apply {
            text=t
            setTextColor(Color.WHITE)
            textSize=size
            if(bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0,dp(4),0,dp(4))
        }
        fun button(t:String, action:()->Unit)=Button(this).apply {
            text=t
            isAllCaps=false
            setOnClickListener { action() }
        }

        root.addView(label("Church Production Pro Remote",22f,true))

        ipBox=EditText(this).apply {
            hint="Master PC IP (example 192.168.1.249)"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType=InputType.TYPE_CLASS_TEXT
        }
        root.addView(ipBox, ViewGroup.LayoutParams(-1,-2))

        pinBox=EditText(this).apply {
            hint="4-digit PIN"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        root.addView(pinBox, ViewGroup.LayoutParams(-1,-2))
        root.addView(button("CONNECT TO MASTER"){ connect() }, ViewGroup.LayoutParams(-1,-2))

        status=label("Not connected",13f).apply { setTextColor(Color.LTGRAY) }
        root.addView(status)

        val transport=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        transport.addView(button("PLAY / PAUSE"){ send("play") }, LinearLayout.LayoutParams(0,-2,1f))
        transport.addView(button("STOP"){ send("stop") }, LinearLayout.LayoutParams(0,-2,1f))
        transport.addView(button("BLACKOUT"){ send("black") }, LinearLayout.LayoutParams(0,-2,1f))
        root.addView(transport, ViewGroup.LayoutParams(-1,-2))

        title=label("No item selected",23f,true)
        root.addView(title)

        current=label("",27f,true).apply {
            gravity=Gravity.CENTER
            minHeight=dp(80)
        }
        root.addView(current, ViewGroup.LayoutParams(-1,-2))

        next=label("",18f).apply {
            gravity=Gravity.CENTER
            setTextColor(Color.LTGRAY)
            minHeight=dp(55)
        }
        root.addView(next, ViewGroup.LayoutParams(-1,-2))

        root.addView(label("SONG SECTIONS",12f,true))
        val secScroll=HorizontalScrollView(this)
        sections=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        secScroll.addView(sections)
        root.addView(secScroll, ViewGroup.LayoutParams(-1,-2))

        root.addView(label("SERVICE",12f,true))
        service=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(service, ViewGroup.LayoutParams(-1,-2))
    }

    private fun connect() {
        val ip=ipBox.text.toString().trim()
        val p=pinBox.text.toString().trim()
        if(ip.isBlank() || p.isBlank()) {
            Toast.makeText(this,"Enter Master IP and PIN",Toast.LENGTH_SHORT).show()
            return
        }
        status.text="Connecting..."
        worker.execute {
            try {
                val full=getJson("http://$ip:45821/net/state?pin=${enc(p)}")
                master=ip
                pin=p
                prefs.edit().putString("ip",ip).putString("pin",p).apply()
                saveMirror(full)
                connected=true
                ui.post {
                    status.text="Connected to $ip"
                    renderFull(full)
                }
            } catch(e:Exception) {
                connected=false
                ui.post {
                    status.text="Connection failed"
                    Toast.makeText(this,"Check IP, PIN, Wi-Fi and Windows Firewall",Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val poller=object:Runnable {
        override fun run() {
            if(connected) poll()
            ui.postDelayed(this,120)
        }
    }

    private fun poll() {
        if(master.isBlank() || pin.isBlank()) return
        worker.execute {
            try {
                val live=getJson("http://$master:45821/net/live?pin=${enc(pin)}")
                val remoteMirror=live.optString("mirror","")
                if(remoteMirror.isNotBlank() && remoteMirror != mirrorSig) {
                    val full=getJson("http://$master:45821/net/state?pin=${enc(pin)}")
                    mirrorSig=remoteMirror
                    saveMirror(full)
                    ui.post { renderFull(full) }
                } else {
                    ui.post { renderLive(live) }
                }
                ui.post { status.text="Connected to $master" }
            } catch(_:Exception) {
                ui.post { status.text="Master connection lost" }
            }
        }
    }

    private fun renderLive(s:JSONObject) {
        title.text=s.optString("title","No item selected")
        current.text=s.optString("lyric","")
        val n=s.optString("nextLyric","")
        next.text=if(n.isBlank()) "" else "NEXT: $n"
        val active=s.optString("activeSection","")
        if(active.isNotBlank()) {
            for(i in 0 until sections.childCount) {
                val b=sections.getChildAt(i) as? Button ?: continue
                b.alpha=if(b.text.toString()==active) 1f else .7f
            }
        }
    }

    private fun renderFull(s:JSONObject) {
        renderLive(s)
        val items=s.optJSONArray("items") ?: JSONArray()
        val selected=s.optInt("selected",-1)
        val sig=items.toString()+"|"+selected
        if(sig != serviceSig) {
            serviceSig=sig
            service.removeAllViews()
            for(i in 0 until items.length()) {
                val name=items.optString(i,"Untitled")
                val b=Button(this).apply {
                    text="${i+1}. $name"
                    isAllCaps=false
                    setTextColor(Color.WHITE)
                    setBackgroundColor(if(i==selected) Color.rgb(38,99,154) else Color.rgb(22,33,44))
                    setOnClickListener { send("sel:$i") }
                }
                service.addView(b, LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,dp(2),0,dp(2)) })
            }
        }
        val a=s.optJSONArray("sections") ?: JSONArray()
        val active=s.optString("activeSection","")
        renderSections(a,active)
    }

    private fun renderSections(a:JSONArray, active:String) {
        val sig=a.toString()+"|"+active
        if(sig==sectionSig) return
        sectionSig=sig
        sections.removeAllViews()
        for(i in 0 until a.length()) {
            val q=a.optJSONObject(i) ?: continue
            val name=q.optString("name","SECTION")
            val index=q.optInt("index",0)
            val b=Button(this).apply {
                text=name
                isAllCaps=false
                setTextColor(Color.WHITE)
                setBackgroundColor(sectionColor(name))
                alpha=if(name==active) 1f else .72f
                setOnClickListener { send("j$index") }
            }
            sections.addView(b)
        }
    }

    private fun sectionColor(name:String)=when(name.trim().uppercase().firstOrNull()) {
        'P'->Color.rgb(35,111,115)
        'C'->Color.rgb(105,63,145)
        'V'->Color.rgb(50,86,140)
        'B'->Color.rgb(145,98,35)
        'T'->Color.rgb(145,55,105)
        'I','O'->Color.rgb(60,90,95)
        else->Color.rgb(45,110,65)
    }

    private fun send(c:String) {
        if(!connected) {
            Toast.makeText(this,"Not connected",Toast.LENGTH_SHORT).show()
            return
        }
        worker.execute {
            try {
                val code=getCode("http://$master:45821/net/cmd?c=${enc(c)}&pin=${enc(pin)}")
                if(code != 204) throw Exception("HTTP $code")
                if(c.startsWith("sel:")) ui.postDelayed({ refreshFull() },160)
            } catch(_:Exception) {
                ui.post { Toast.makeText(this,"Remote command failed",Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun refreshFull() {
        if(!connected) return
        worker.execute {
            try {
                val full=getJson("http://$master:45821/net/state?pin=${enc(pin)}")
                saveMirror(full)
                ui.post { renderFull(full) }
            } catch(_:Exception) {}
        }
    }

    private fun saveMirror(s:JSONObject) {
        try { mirrorFile.writeText(s.toString()) } catch(_:Exception) {}
    }

    private fun loadMirror() {
        try {
            if(mirrorFile.exists()) renderFull(JSONObject(mirrorFile.readText()))
        } catch(_:Exception) {}
    }

    private fun getJson(u:String):JSONObject {
        val c=URL(u).openConnection() as HttpURLConnection
        c.connectTimeout=1800
        c.readTimeout=1800
        c.requestMethod="GET"
        c.useCaches=false
        try {
            if(c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    private fun getCode(u:String):Int {
        val c=URL(u).openConnection() as HttpURLConnection
        c.connectTimeout=1800
        c.readTimeout=1800
        c.requestMethod="GET"
        c.useCaches=false
        return try { c.responseCode } finally { c.disconnect() }
    }

    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")

    override fun onDestroy() {
        ui.removeCallbacks(poller)
        worker.shutdownNow()
        super.onDestroy()
    }
}
