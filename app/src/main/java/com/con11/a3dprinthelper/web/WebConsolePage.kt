package com.con11.a3dprinthelper.web

object WebConsolePage {
    val html: String = """
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>3DPrintHelper Web Monitor</title>
  <style>
    :root {
      color-scheme: dark;
      --bg:#101418; --panel:#1b2229; --panel2:#222b33; --line:#33404a;
      --text:#e6edf3; --muted:#91a2af; --accent:#7dd3fc; --good:#1b7f54;
      --warn:#e98024; --bad:#d32f2f;
    }
    * { box-sizing: border-box; }
    body { margin:0; background:var(--bg); color:var(--text); font-family: ui-sans-serif, "Noto Sans SC", sans-serif; }
    .app { min-height:100vh; padding:18px; display:grid; grid-template-columns:minmax(0,3fr) minmax(280px,1fr); gap:16px; }
    .videoPanel, .side, .settings { background:var(--panel); border:1px solid var(--line); border-radius:8px; }
    .videoPanel { position:relative; overflow:hidden; min-height:360px; display:flex; align-items:center; justify-content:center; }
    .videoPanel img { width:100%; aspect-ratio:4/3; object-fit:contain; background:#000; display:block; }
    .overlay { position:absolute; left:14px; bottom:14px; max-width:520px; padding:14px; border-radius:8px; background:rgba(16,20,24,.82); backdrop-filter:blur(10px); }
    .overlay.bad { background:rgba(139,31,24,.88); } .overlay.warn { background:rgba(132,68,15,.88); } .overlay.good { background:rgba(15,88,57,.88); }
    h1 { margin:0 0 8px; font-size:22px; letter-spacing:0; }
    h2 { margin:0 0 12px; font-size:16px; }
    p { margin:4px 0; color:var(--muted); }
    .row { display:flex; gap:10px; flex-wrap:wrap; align-items:center; }
    .metric { font-size:13px; color:#d6e1ea; }
    .side { padding:14px; display:flex; flex-direction:column; gap:10px; }
    button { border:0; border-radius:8px; padding:11px 12px; background:var(--accent); color:#082f49; font-weight:700; cursor:pointer; }
    button.secondary { background:transparent; color:var(--text); border:1px solid #60717e; }
    button.fieldAction { margin-top:8px; padding:8px 10px; font-size:12px; }
    button.danger { background:var(--bad); color:white; }
    .settings { grid-column:1/-1; padding:16px; }
    .settingsMasonry { display:grid; grid-template-columns:repeat(3,minmax(220px,1fr)); gap:14px; align-items:start; }
    .settingsColumn { display:flex; flex-direction:column; gap:14px; min-width:0; }
    .settingsSection { min-width:0; }
    .settingsSection.compact { max-width:320px; }
    label { display:block; font-size:12px; color:var(--muted); margin-bottom:6px; }
    input, textarea, select { width:100%; border:1px solid #60717e; border-radius:8px; background:#101820; color:var(--text); padding:10px; }
    input[type="checkbox"] { width:auto; padding:0; accent-color:var(--accent); }
    input[type="range"] { padding:0; }
    textarea { min-height:150px; resize:vertical; }
    .field { margin-bottom:10px; }
    .fieldHeader { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-bottom:6px; }
    .fieldHeader label { margin:0; }
    .rangeValue { min-width:62px; color:var(--text); font-size:12px; font-variant-numeric:tabular-nums; text-align:right; }
    .field.booleanField { display:flex; align-items:center; min-height:40px; }
    .field.booleanField label { margin:0; display:flex; align-items:center; gap:10px; font-size:15px; color:var(--text); }
    .notice { color:#ffcf9f; font-size:13px; }
    @media (max-width: 860px) { .app { grid-template-columns:1fr; padding:10px; } .settingsMasonry { grid-template-columns:1fr; } .overlay { position:static; width:100%; max-width:none; border-radius:0; } .videoPanel { display:block; } }
  </style>
</head>
<body>
  <main class="app">
    <section class="videoPanel">
      <img id="stream" alt="camera stream">
      <div id="overlay" class="overlay">
        <h1>3D 打印巡检</h1>
        <div class="row">
          <span class="metric" id="runState">--</span>
          <span class="metric" id="countdown">下次：--:--</span>
          <span class="metric" id="torch">补光 --</span>
          <span class="metric" id="cameraSource">相机 --</span>
          <span class="metric" id="keepAliveState">保活 --</span>
        </div>
        <p id="summary">连接中...</p>
        <div class="row">
          <span class="metric" id="lastTime">最近 --</span>
          <span class="metric" id="model">模型 --</span>
          <span class="metric" id="confidence">置信度 --</span>
          <span class="metric" id="previewMeta">预览 --</span>
        </div>
        <p id="error"></p>
      </div>
    </section>
    <aside class="side">
      <button id="startBtn">开始巡视</button>
      <button class="secondary" data-action="analyze_now">立即分析</button>
      <button class="secondary" id="torchBtn">开启闪光灯</button>
      <button class="secondary" id="screenBtn">息屏</button>
      <button class="secondary" data-action="test_bark">测试 Bark</button>
      <p class="notice">无密码局域网访问，请只在可信 Wi-Fi 使用。</p>
      <p id="webUrl"></p>
    </aside>
    <section class="settings">
      <div id="settings" class="settingsMasonry"></div>
    </section>
    <section class="settings">
      <div>
        <h2>设置操作</h2>
        <button id="saveBtn">保存设置</button>
        <p id="saveState"></p>
      </div>
    </section>
  </main>
  <script>
    let schema = null;
    let running = false;
    let streamRetryTimer = null;
    const post = (url, data) => fetch(url, {method:"POST", headers:{"Content-Type":"application/json; charset=utf-8"}, body:JSON.stringify(data)});
    function fmtTime(ms){ return ms ? new Date(ms).toLocaleTimeString() : "--"; }
    function countdown(ms){ if(!ms) return "下次：--:--"; const d=Math.max(0,ms-Date.now()); return "下次：" + String(Math.floor(d/60000)).padStart(2,"0") + ":" + String(Math.floor((d%60000)/1000)).padStart(2,"0"); }
    function connectStream(delayMs){
      clearTimeout(streamRetryTimer);
      streamRetryTimer=setTimeout(()=>{
        const stream=document.getElementById("stream");
        stream.src="/stream.mjpg?t="+Date.now();
      }, delayMs||0);
    }
    async function waitForServer(url, timeoutMs){
      const deadline=Date.now()+timeoutMs;
      while(Date.now()<deadline){
        try {
          const response=await fetch(url+"api/status?t="+Date.now(),{cache:"no-store"});
          if(response.ok) return true;
        } catch(e) {}
        await new Promise(resolve=>setTimeout(resolve,400));
      }
      return false;
    }
    function updateRangeValue(field){
      const input=document.getElementById(field.key);
      const value=document.getElementById(field.key+"Value");
      if(input&&value) value.textContent=input.value+(field.unit ? " "+field.unit : "");
    }
    function fieldElement(field) {
      let el;
      if(field.type==="enum"){
        el=document.createElement("select");
        (field.options||[]).forEach(o=>{const option=document.createElement("option"); option.value=o.value; option.textContent=o.label; el.appendChild(option);});
      } else if(field.type==="textarea"){
        el=document.createElement("textarea"); el.rows=field.rows||4;
      } else {
        el=document.createElement("input");
        el.type=field.type==="secret" ? "password" : field.type==="boolean" ? "checkbox" : field.type==="slider" ? "range" : field.type==="number" ? "number" : "text";
        if(field.min!==undefined) el.min=field.min;
        if(field.max!==undefined) el.max=field.max;
        if(field.type==="secret") el.autocomplete="off";
      }
      el.id=field.key; el.dataset.settingType=field.type; return el;
    }
    function renderSchema(){
      const root=document.getElementById("settings"); root.replaceChildren();
      const columns = Array.from({length: window.innerWidth <= 860 ? 1 : 3}, () => {
        const column = document.createElement("div");
        column.className = "settingsColumn";
        root.appendChild(column);
        return { node: column, weight: 0 };
      });
      const fieldWeight = (field) => {
        if(field.type==="textarea") return 6;
        if(field.type==="slider") return 2;
        if(field.type==="boolean") return 1;
        return 2;
      };
      schema.sections.forEach(section=>{
        const group=document.createElement("div"); group.className="settingsSection";
        if(section.fields.length===1 && section.fields[0].type==="boolean") group.classList.add("compact");
        const title=document.createElement("h2"); title.textContent=section.title; group.appendChild(title);
        let groupWeight = 1;
        section.fields.forEach(field=>{
          const wrap=document.createElement("div"); wrap.className="field";
          const input=fieldElement(field);
          if(field.type==="boolean"){
            wrap.classList.add("booleanField");
            const label=document.createElement("label");
            label.htmlFor=field.key;
            label.appendChild(input);
            label.appendChild(document.createTextNode(field.label));
            wrap.appendChild(label);
          } else {
            const label=document.createElement("label"); label.htmlFor=field.key; label.textContent=field.label+(field.unit ? "（"+field.unit+"）" : "")+(field.webWriteOnly ? "（留空表示不修改）" : "");
            if(field.type==="slider"){
              const header=document.createElement("div"); header.className="fieldHeader";
              const value=document.createElement("span"); value.id=field.key+"Value"; value.className="rangeValue";
              header.appendChild(label); header.appendChild(value);
              input.addEventListener("input",()=>updateRangeValue(field));
              wrap.appendChild(header); wrap.appendChild(input);
            } else {
              wrap.appendChild(label); wrap.appendChild(input);
            }
          }
          if(field.resetAction){
            const reset=document.createElement("button");
            reset.type="button"; reset.className="secondary fieldAction";
            reset.textContent=field.resetAction==="defaultPrompt" ? "恢复默认提示词" : "恢复默认值";
            reset.onclick=async()=>{
              const defaults=await (await fetch("/api/settings/defaults",{cache:"no-store"})).json();
              if(defaults[field.key]!==undefined) input.value=defaults[field.key];
            };
            wrap.appendChild(reset);
          }
          group.appendChild(wrap);
          groupWeight += fieldWeight(field);
        });
        const target = columns.reduce((best, current) => current.weight < best.weight ? current : best, columns[0]);
        target.node.appendChild(group);
        target.weight += groupWeight;
      });
    }
    async function loadSettings(){
      if(!schema){ schema=await (await fetch("/api/settings/schema",{cache:"no-store"})).json(); renderSchema(); }
      const s=await (await fetch("/api/settings",{cache:"no-store"})).json();
      schema.sections.flatMap(x=>x.fields).forEach(field=>{ const el=document.getElementById(field.key); if(!el || s[field.key]===undefined || field.webWriteOnly) return; if(el.type==="checkbox") el.checked=!!s[field.key]; else el.value=s[field.key]; if(field.type==="slider") updateRangeValue(field); });
    }
    async function refresh(){
      try {
        const s = await (await fetch("/api/status", {cache:"no-store"})).json();
        running = s.isRunning;
        const r = s.lastResult;
        document.getElementById("startBtn").textContent = running ? "暂停巡视" : "开始巡视";
        document.getElementById("torchBtn").textContent = s.torchEnabled ? "关闭闪光灯" : "开启闪光灯";
        document.getElementById("screenBtn").textContent = s.screenOff ? "点亮屏幕" : "息屏";
        document.getElementById("runState").textContent = running ? "运行中" : "已暂停";
        document.getElementById("countdown").textContent = countdown(s.nextCaptureAtMillis);
        document.getElementById("torch").textContent = "补光 " + (s.torchEnabled ? "开" : "关");
        document.getElementById("cameraSource").textContent = "相机 " + (s.cameraSource || "--");
        document.getElementById("keepAliveState").textContent = s.keepAliveTemporarilyStopped ? "保活 已临时停止" : s.keepAliveServiceRunning ? "保活 运行中" : "保活 已停止";
        document.getElementById("summary").textContent = r ? r.summary : s.statusMessage;
        document.getElementById("lastTime").textContent = "最近 " + fmtTime(s.lastAnalysisAtMillis);
        document.getElementById("model").textContent = "模型 " + s.settings.openAiModel;
        document.getElementById("confidence").textContent = r ? ("置信度 " + Number(r.confidence).toFixed(2)) : "置信度 --";
        document.getElementById("previewMeta").textContent = "预览 " + s.webPreviewScalePercent + "% · " + s.webPreviewFps + " FPS";
        document.getElementById("error").textContent = s.cameraError || s.errorMessage || "";
        document.getElementById("webUrl").textContent = s.webUrl;
        const overlay=document.getElementById("overlay"); overlay.className="overlay";
        if(s.printStatus==="Abnormal") overlay.classList.add("bad"); else if(s.printStatus==="Warning") overlay.classList.add("warn"); else if(s.printStatus==="Normal") overlay.classList.add("good");
      } catch(e) { document.getElementById("summary").textContent = "连接已断开"; }
    }
    document.getElementById("startBtn").onclick = () => post("/api/control", {action: running ? "stop" : "start"}).then(refresh);
    document.getElementById("torchBtn").onclick = () => post("/api/control", {action:"toggle_torch"}).then(refresh);
    document.getElementById("screenBtn").onclick = async () => {
      const button=document.getElementById("screenBtn");
      button.disabled=true;
      try { await post("/api/control", {action: button.textContent.includes("点亮") ? "screen_on" : "screen_off"}); }
      finally { setTimeout(()=>{ button.disabled=false; refresh(); }, 200); }
    };
    document.querySelectorAll("[data-action]").forEach(b => b.onclick = () => post("/api/control", {action:b.dataset.action}).then(refresh));
    document.getElementById("saveBtn").onclick = async () => {
      const button=document.getElementById("saveBtn"), state=document.getElementById("saveState"), data={}; button.disabled=true; state.textContent="正在保存...";
      schema.sections.flatMap(x=>x.fields).forEach(field=>{ const el=document.getElementById(field.key); if(!el || (field.webWriteOnly && !el.value.trim())) return; data[field.key]=(field.type==="number"||field.type==="slider") ? Number(el.value) : field.type==="boolean" ? el.checked : el.value; });
      try {
        const response=await post("/api/settings",data); if(!response.ok) throw new Error(await response.text());
        const result=await response.json(), target=result.reloadUrl || window.location.href;
        state.textContent="保存成功，正在等待服务刷新...";
        await new Promise(resolve=>setTimeout(resolve,result.reloadAfterMs||700));
        const ready=await waitForServer(target,10000);
        if(ready) window.location.replace(target+"?t="+Date.now());
        else { button.disabled=false; state.textContent="设置已保存，Web 服务仍在启动"; connectStream(0); }
      } catch(e) { button.disabled=false; state.textContent="保存失败："+e.message; }
    };
    window.addEventListener("resize", () => {
      const nextColumnCount = window.innerWidth <= 860 ? 1 : 3;
      if(document.querySelectorAll(".settingsColumn").length !== nextColumnCount) loadSettings();
    });
    const stream=document.getElementById("stream");
    stream.addEventListener("error",()=>connectStream(1000));
    stream.addEventListener("load",()=>clearTimeout(streamRetryTimer));
    connectStream(0); loadSettings(); refresh(); setInterval(refresh, 1000);
  </script>
</body>
</html>
    """.trimIndent()
}
