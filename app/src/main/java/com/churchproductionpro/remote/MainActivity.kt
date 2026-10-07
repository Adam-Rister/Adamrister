package com.churchproductionpro.remote

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
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

    private lateinit var root: LinearLayout
    private lateinit var connectionPanel: LinearLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var remotePanel: LinearLayout
    private lateinit var ipBox: EditText
    private lateinit var pinBox: EditText
    private lateinit var settingsIpBox: EditText
    private lateinit var settingsPinBox: EditText
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

        val savedIp=prefs.getString("ip","") ?: ""
        val savedPin=prefs.getString("pin","") ?: ""
        ipBox.setText(savedIp)
        pinBox.setText(savedPin)
        settingsIpBox.setText(savedIp)
        settingsPinBox.setText(savedPin)

        loadMirror()
        ui.post(poller)

        if(savedIp.isNotBlank() && savedPin.isNotBlank()) {
            showRemote()
            status.text="Connecting to saved Master..."
            connectTo(savedIp,savedPin,true)
        } else {
            showFirstConnect()
        }
    }

    private fun buildUi() {
        val scroll=ScrollView(this)
        root=LinearLayout(this).apply {
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

        val header=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        header.addView(label("Church Production Pro Remote",22f,true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("SETTINGS"){ openSettings() })
        root.addView(header,ViewGroup.LayoutParams(-1,-2))

        connectionPanel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(0,dp(6),0,dp(6))
        }
        connectionPanel.addView(label("CONNECT TO MASTER",13f,true))
        ipBox=EditText(this).apply {
            hint="Master PC IP (example 192.168.1.249)"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType=InputType.TYPE_CLASS_TEXT
        }
        connectionPanel.addView(ipBox, ViewGroup.LayoutParams(-1,-2))
        pinBox=EditText(this).apply {
            hint="4-digit PIN"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        connectionPanel.addView(pinBox, ViewGroup.LayoutParams(-1,-2))
        connectionPanel.addView(button("CONNECT TO MASTER"){
            connectTo(ipBox.text.toString().trim(),pinBox.text.toString().trim(),false)
        }, ViewGroup.LayoutParams(-1,-2))
        root.addView(connectionPanel,ViewGroup.LayoutParams(-1,-2))

        settingsPanel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            visibility=View.GONE
            setPadding(0,dp(6),0,dp(6))
        }
        settingsPanel.addView(label("REMOTE SETTINGS",18f,true))
        settingsIpBox=EditText(this).apply {
            hint="Master PC IP"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType=InputType.TYPE_CLASS_TEXT
        }
        settingsPanel.addView(settingsIpBox,ViewGroup.LayoutParams(-1,-2))
        settingsPinBox=EditText(this).apply {
            hint="4-digit PIN"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        settingsPanel.addView(settingsPinBox,ViewGroup.LayoutParams(-1,-2))
        settingsPanel.addView(button("SAVE & CONNECT"){
            connectTo(settingsIpBox.text.toString().trim(),settingsPinBox.text.toString().trim(),false)
        },ViewGroup.LayoutParams(-1,-2))
        settingsPanel.addView(button("DISCONNECT / CHANGE MASTER"){ disconnectChangeMaster() },ViewGroup.LayoutParams(-1,-2))
        settingsPanel.addView(button("BACK TO REMOTE"){ closeSettings() },ViewGroup.LayoutParams(-1,-2))
        root.addView(settingsPanel,ViewGroup.LayoutParams(-1,-2))

        status=label("Not connected",13f).apply { setTextColor(Color.LTGRAY) }
        root.addView(status)

        remotePanel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }

        val transport=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        transport.addView(button("PLAY / PAUSE"){ send("play") }, LinearLayout.LayoutParams(0,-2,1f))
        transport.addView(button("STOP"){ send("stop") }, LinearLayout.LayoutParams(0,-2,1f))
        transport.addView(button("BLACKOUT"){ send("black") }, LinearLayout.LayoutParams(0,-2,1f))
        remotePanel.addView(transport, ViewGroup.LayoutParams(-1,-2))

        title=label("No item selected",23f,true)
        remotePanel.addView(title)

        current=label("",27f,true).apply {
            gravity=Gravity.CENTER
            minHeight=dp(80)
        }
        remotePanel.addView(current, ViewGroup.LayoutParams(-1,-2))

        next=label("",18f).apply {
            gravity=Gravity.CENTER
            setTextColor(Color.LTGRAY)
            minHeight=dp(55)
        }
        remotePanel.addView(next, ViewGroup.LayoutParams(-1,-2))

        remotePanel.addView(label("SONG SECTIONS",12f,true))
        val secScroll=HorizontalScrollView(this)
        sections=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        secScroll.addView(sections)
        remotePanel.addView(secScroll, ViewGroup.LayoutParams(-1,-2))

        remotePanel.addView(label("SERVICE",12f,true))
        service=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        remotePanel.addView(service, ViewGroup.LayoutParams(-1,-2))

        root.addView(remotePanel,ViewGroup.LayoutParams(-1,-2))
    }

    private fun showRemote() {
        connectionPanel.visibility=View.GONE
        settingsPanel.visibility=View.GONE
        remotePanel.visibility=View.VISIBLE
    }

    private fun showFirstConnect() {
        settingsPanel.visibility=View.GONE
        connectionPanel.visibility=View.VISIBLE
        remotePanel.visibility=View.VISIBLE
        status.text="Not connected"
    }

    private fun openSettings() {
        val ip=if(master.isNotBlank()) master else prefs.getString("ip","") ?: ""
        val p=if(pin.isNotBlank()) pin else prefs.getString("pin","") ?: ""
        settingsIpBox.setText(ip)
        settingsPinBox.setText(p)
        connectionPanel.visibility=View.GONE
        remotePanel.visibility=View.GONE
        settingsPanel.visibility=View.VISIBLE
    }

    private fun closeSettings() {
        val savedIp=prefs.getString("ip","") ?: ""
        val savedPin=prefs.getString("pin","") ?: ""
        if(connected || (savedIp.isNotBlank() && savedPin.isNotBlank())) showRemote() else showFirstConnect()
    }

    private fun disconnectChangeMaster() {
        connected=false
        val oldIp=if(master.isNotBlank()) master else settingsIpBox.text.toString().trim()
        master=""
        pin=""
        mirrorSig=""
        prefs.edit().remove("ip").remove("pin").apply()
        ipBox.setText(oldIp)
        pinBox.setText("")
        settingsIpBox.setText(oldIp)
        settingsPinBox.setText("")
        showFirstConnect()
        status.text="Disconnected — enter Master IP and PIN"
    }

    private fun connectTo(ip:String,p:String,automatic:Boolean) {
        if(ip.isBlank() || p.isBlank()) {
            Toast.makeText(this,"Enter Master IP and PIN",Toast.LENGTH_SHORT).show()
            return
        }

        status.text=if(automatic) "Connecting to saved Master..." else "Connecting..."

        worker.execute {
            try {
                val full=getJson("http://"+ip+":45821/net/state?pin="+enc(p))
                val live=getJson("http://"+ip+":45821/net/live?pin="+enc(p))
                master=ip
                pin=p
                mirrorSig=live.optString("mirror","")
                prefs.edit().putString("ip",ip).putString("pin",p).apply()
                saveMirror(full)
                connected=true
                ui.post {
                    ipBox.setText(ip)
                    pinBox.setText(p)
                    settingsIpBox.setText(ip)
                    settingsPinBox.setText(p)
                    showRemote()
                    status.text="Connected to "+ip
                    renderFull(full)
                    renderLive(live)
                }
            } catch(e:Exception) {
                connected=false
                ui.post {
                    status.text="Could not connect to saved Master"
                    if(!automatic) showFirstConnect()
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
                val live=getJson("http://"+master+":45821/net/live?pin="+enc(pin))
                val remoteMirror=live.optString("mirror","")
                if(remoteMirror.isNotBlank() && remoteMirror != mirrorSig) {
                    val full=getJson("http://"+master+":45821/net/state?pin="+enc(pin))
                    mirrorSig=remoteMirror
                    saveMirror(full)
                    ui.post { renderFull(full) }
                } else {
                    ui.post { renderLive(live) }
                }
                ui.post { status.text="Connected to "+master }
            } catch(_:Exception) {
                ui.post { status.text="Master connection lost — retrying..." }
            }
        }
    }

    private fun renderLive(s:JSONObject) {
        title.text=s.optString("title","No item selected")
        current.text=s.optString("lyric","")
        val n=s.optString("nextLyric","")
        next.text=if(n.isBlank()) "" else "NEXT: "+n
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
                    text=(i+1).toString()+". "+name
                    isAllCaps=false
                    setTextColor(Color.WHITE)
                    setBackgroundColor(if(i==selected) Color.rgb(38,99,154) else Color.rgb(22,33,44))
                    setOnClickListener { send("sel:"+i) }
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
                setOnClickListener { send("j"+index) }
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
                val code=getCode("http://"+master+":45821/net/cmd?c="+enc(c)+"&pin="+enc(pin))
                if(code != 204) throw Exception("HTTP "+code)
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
                val full=getJson("http://"+master+":45821/net/state?pin="+enc(pin))
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
            if(c.responseCode != 200) throw Exception("HTTP "+c.responseCode)
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
