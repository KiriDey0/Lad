// SPDX-License-Identifier: AGPL-3.0-only
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("../apps/macos/node_modules/playwright");
const directory = path.resolve(__dirname, "../work/design-comparison");
const manifest = JSON.parse(
  fs.readFileSync(path.join(directory, "manifest.json"), "utf8"),
);
const screenLabels = Object.fromEntries(manifest.screens);
const html = `<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Лад — Classic / Orbit</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#101115;color:#eef0f6;font:15px/1.5 system-ui,sans-serif}header{padding:36px max(24px,4vw) 24px;border-bottom:1px solid #ffffff20}h1{font-size:30px;letter-spacing:-1px;margin:0 0 10px}p{color:#aeb4c3;max-width:950px;margin:8px 0}button,select,a{font:inherit}button,select{border:1px solid #ffffff25;border-radius:999px;padding:10px 16px;background:#252833;color:inherit;cursor:pointer}button[aria-pressed=true]{background:#bfcaf5;color:#111728}nav{display:flex;flex-wrap:wrap;gap:10px;margin-top:20px;align-items:center}a{color:#c7d3ff;text-decoration:none}main{padding:24px;display:grid;gap:32px}article{scroll-margin:24px}h2{font-size:20px;margin:0 0 12px}section.pair{position:relative;border:1px solid #ffffff24;border-radius:18px;overflow:hidden;background:#090a0c}.images{display:grid;grid-template-columns:1fr 1fr;gap:1px;background:#ffffff25}.frame{position:relative;min-width:0;margin:0;background:#090a0c}.frame figcaption{display:flex;justify-content:space-between;align-items:center;padding:10px 16px;color:#bfc5d4;background:#20222a;font-size:13px}.frame img{display:block;width:100%;height:auto}.frame a:hover{color:white}.overlay .images{display:block;position:relative}.overlay .frame:first-child{position:absolute;inset:0;z-index:2;clip-path:inset(0 calc(100% - var(--reveal,50%)) 0 0);pointer-events:none}.overlay .frame img{width:100%}.overlay .frame figcaption{display:none}.compare-slider{display:none;padding:12px 16px;background:#20222a}.overlay .compare-slider{display:flex;align-items:center;gap:16px}.compare-slider input{flex:1;accent-color:#bfcaf5;min-width:0}.foot{padding:12px 4px;color:#aeb4c3;font-size:13px;display:flex;gap:16px;flex-wrap:wrap}.badge{font-variant-numeric:tabular-nums}.pair-only{background:#090a0c}.pair-only header,.pair-only .foot,.pair-only .compare-slider{display:none}.pair-only main{padding:0;gap:0}.pair-only article>h2{margin:0;padding:12px 20px;background:#161820;font-size:19px}.pair-only .pair{border:0;border-radius:0}@media(max-width:700px){header{padding:24px}h1{font-size:24px}main{padding:16px}.images{grid-template-columns:1fr}.frame figcaption{padding:8px 12px}nav{gap:8px}button,select{font-size:13px}}
</style>
<header><h1>Лад: Classic и Orbit</h1><p>22 пары / 44 исходных скриншота из настоящих приложений. Слева — прежний дизайн 0.6.4, справа — Orbit на той же базе. Одинаковые окно, масштаб, демобиблиотека, очередь, устройства и позиция трека 1:06.</p><p>Основные окна: 1440 × 847 при 85% и 1100 × 800 при 100%. Это сравнение дизайна, данные демонстрационные. В Orbit уже исправлены отступы карточек, компактное окно устройств, палитра и ширина сегментов.</p><nav><select id="size" aria-label="Размер окна">${manifest.sizes.map((s) => `<option value="${s.id}">${s.width} × ${s.height} · ${s.scale}%</option>`).join("")}</select><select id="screen" aria-label="Экран"><option value="all">Все 11 экранов</option>${manifest.screens.map(([id, label]) => `<option value="${id}">${label}</option>`).join("")}</select><button id="side" aria-pressed="true">Рядом</button><button id="overlay" aria-pressed="false">Наложение</button><a href="manifest.json">Параметры съёмки</a></nav></header><main id="gallery"></main>
<script>
const data=${JSON.stringify(manifest)};
const gallery=document.getElementById('gallery'),size=document.getElementById('size'),screen=document.getElementById('screen');
let overlay=false;
const params=new URLSearchParams(location.search);
if(params.has('size'))size.value=params.get('size');if(params.has('screen'))screen.value=params.get('screen');
if(params.has('pair'))document.body.classList.add('pair-only');
function render(){const spec=data.sizes.find(s=>s.id===size.value);gallery.innerHTML=data.screens.filter(([id])=>screen.value==='all'||screen.value===id).map(([id,label])=>{const left=size.value+'-'+id+'-classic.png',right=size.value+'-'+id+'-orbit.png';return '<article><h2>'+label+' <span class="badge">· '+spec.width+' × '+spec.height+' / '+spec.scale+'%</span></h2><section class="pair '+(overlay?'overlay':'')+'"><div class="images"><figure class="frame"><figcaption><span>Classic · 0.6.4</span><a target="_blank" href="'+left+'">Оригинал ↗</a></figcaption><img loading="eager" src="'+left+'" alt="'+label+' — Classic"></figure><figure class="frame"><figcaption><span>Orbit · тестовая</span><a target="_blank" href="'+right+'">Оригинал ↗</a></figcaption><img loading="eager" src="'+right+'" alt="'+label+' — Orbit"></figure></div><label class="compare-slider">Classic <input type="range" min="0" max="100" value="50" aria-label="Граница сравнения"> Orbit</label></section><div class="foot"><a href="pairs/'+size.value+'-'+id+'.png" target="_blank">Парный PNG ↗</a><a href="'+left+'" target="_blank">Classic в полном размере ↗</a><a href="'+right+'" target="_blank">Orbit в полном размере ↗</a></div></article>'}).join('');}
gallery.oninput=event=>{if(event.target.matches('input[type="range"]'))event.target.closest('.pair').style.setProperty('--reveal',event.target.value+'%')};
size.onchange=screen.onchange=render;
for(const id of ['side','overlay'])document.getElementById(id).onclick=()=>{overlay=id==='overlay';document.getElementById('side').setAttribute('aria-pressed',String(!overlay));document.getElementById('overlay').setAttribute('aria-pressed',String(overlay));render()};render();
</script></html>`;
fs.writeFileSync(path.join(directory, "index.html"), html);
(async () => {
  fs.mkdirSync(path.join(directory, "pairs"), { recursive: true });
  const browser = await chromium.launch({
    headless: true,
    executablePath: chromium.executablePath(),
  });
  try {
    const page = await browser.newPage({
      viewport: { width: 2400, height: 1000 },
      deviceScaleFactor: 1,
    });
    for (const spec of manifest.sizes)
      for (const [id] of manifest.screens) {
        await page.goto(
          "file://" +
            path.join(directory, "index.html") +
            `?pair=1&size=${spec.id}&screen=${id}`,
        );
        await page
          .locator("img")
          .evaluateAll((images) =>
            Promise.all(images.map((image) => image.decode())),
          );
        await page.screenshot({
          path: path.join(directory, "pairs", `${spec.id}-${id}.png`),
          fullPage: true,
        });
      }
    await page.goto("file://" + path.join(directory, "index.html"));
    if ((await page.locator("article").count()) !== 11)
      throw Error("Incomplete gallery");
    await page.getByRole("button", { name: "Наложение", exact: true }).click();
    if ((await page.locator(".overlay").count()) !== 11)
      throw Error("Comparison overlay failed");
    await page
      .getByLabel("Размер окна", { exact: true })
      .selectOption("compact");
    if (
      !(await page
        .locator("h2")
        .first()
        .innerText()
        .then((s) => s.includes("1100")))
    )
      throw Error("Viewport selector failed");
    console.log(
      "Gallery verified: 22 pairs, 44 original screenshots, overlay and size selector.",
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
