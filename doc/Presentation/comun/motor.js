/* ============================================================
   PIXEL ART (sprites originales)
   ============================================================ */
const PAL={k:'#1a1a1a',p:'#ff7fc0',P:'#e0559f',s:'#ffd9b8',r:'#ff7a6b',g:'#8aa24a',G:'#5f7a2d',Y:'#f6d36b',y:'#dba93b',w:'#ffffff',N:'#d9954f',j:'#7a4a1f',R:'#e23d4f'};
const _c={};
function px(rows,scale=1){
  const key=rows.join('|')+'@'+scale; if(_c[key]) return _c[key];
  const cv=document.createElement('canvas'); cv.width=rows[0].length*scale; cv.height=rows.length*scale;
  const g=cv.getContext('2d');
  rows.forEach((r,y)=>[...r].forEach((ch,x)=>{ if(PAL[ch]){ g.fillStyle=PAL[ch]; g.fillRect(x*scale,y*scale,scale,scale);} }));
  return _c[key]=cv.toDataURL();
}
const HAT=["...kkkkkk...","..kppppppk..",".kppppppppk.",".kPPPPPPPPk."];
const BODY=["..kkkkkkkk..","..kggggggk..",".kggggggggk.",".kggggggggk.","..kggkkggk..","..kkk..kkk.."];
const FACE={
  neutral:{skin:'s',rows:["ssssssss","ssksskss","ssksskss","ssskksss"]},
  angry:  {skin:'r',rows:["skssssks","ssksskss","ssksskss","sskwwkss"]},
  bored:  {skin:'s',rows:["ssssssss","skksskks","ssssssss","sskkkkss"]},
  happy:  {skin:'s',rows:["ssssssss","ssksskss","skskksks","skwwwwks"]}
};
function heroRows(mood,frame=0){
  const f=FACE[mood]||FACE.neutral;
  const face=f.rows.map(r=>".k"+r.replace(/s/g,f.skin)+"k.");
  const body=BODY.slice(); if(frame===1) body[5]=".kkk....kkk.";
  return [...HAT,...face,...body];
}
const heroURL=(m,s,fr=0)=>px(heroRows(m,fr),s);
const ART={
  coin:["..kkkk..",".kYYYYk.","kYYwYYyk","kYYYYYyk","kYYYYYyk","kYYYYYyk",".kYYYyk.","..kkkk.."],
  check:["......gg",".....ggg","g...ggg.","gg.ggg..","gggggg..",".gggg...","..gg...."],
  cloud:["....kkkk........","..kkwwwwkkk.....",".kwwwwwwwwwkk...","kwwwwwwwwwwwwkk.","kwwwwwwwwwwwwwwk",".kkkkkkkkkkkkkk."],
  warn:["kkkkkkkkk","kYYYYYYYk","kYYYkYYYk","kYYYkYYYk","kYYYkYYYk","kYYYYYYYk","kYYYkYYYk","kYYYYYYYk","kkkkkkkkk"],
  brick:["kkkkkkkk","NNNjNNNN","NNNjNNNN","NNNjNNNN","jjjjjjjj","NNNNNjNN","NNNNNjNN","NNNNNjNN"].map(r=>r.replace(/k/g,'j'))
};
function sprSrc(spec,scale){
  const [n,m]=spec.split(':');
  return n==='hero'?heroURL(m||'neutral',scale):px(ART[n],scale);
}
document.querySelectorAll('[data-spr]').forEach(el=>{ el.src=sprSrc(el.dataset.spr,+el.dataset.s||1); });
document.documentElement.style.setProperty('--brick',`url(${px(ART.brick,5)})`);
function playerSrc(mood){
  const o=CONFIG.sprites.player;
  if(typeof o==='string'&&o) return o;
  if(o&&typeof o==='object'&&o[mood]) return o[mood];
  return heroURL(mood,5);
}

/* ============================================================
   MOTOR DE DIAPOSITIVAS (con sub-pasos)
   ============================================================ */
const stage=document.getElementById('stage');
const slides=[...document.querySelectorAll('.slide')];
const reduce=matchMedia('(prefers-reduced-motion: reduce)').matches;
const H={};
let cur=0, step=0, busy=false;
const mn=s=>+s.dataset.min||0, mx=s=>+s.dataset.max||0;

function fit(){ const s=Math.min(innerWidth/1280,innerHeight/720); stage.style.transform=`scale(${s})`; }
addEventListener('resize',fit); fit();

function applyAt(sl,n){
  sl.querySelectorAll('[data-at]').forEach(e=>e.classList.toggle('show',+e.dataset.at<=n));
}
function setStep(n,dir){
  const old=step; step=n; const sl=slides[cur]; sl.dataset.step=n;
  applyAt(sl,n); H[sl.id]?.step?.(n,old,dir); updateNav();
}
function next(){ if(busy)return; const s=slides[cur]; if(step<mx(s)) setStep(step+1,'fwd'); else if(cur<slides.length-1) goSlide(cur+1,'fwd'); }
function prev(){ if(busy)return; const s=slides[cur]; if(step>mn(s)) setStep(step-1,'back'); else if(cur>0) goSlide(cur-1,'back'); }
async function goSlide(n,dir){
  busy=true;
  await wipe(()=>{
    H[slides[cur].id]?.leave?.();
    slides[cur].classList.remove('active'); cur=n; const sl=slides[cur]; sl.classList.add('active');
    step=dir==='fwd'?mn(sl):mx(sl); sl.dataset.step=step; applyAt(sl,step);
    H[sl.id]?.enter?.(step,dir); H[sl.id]?.step?.(step,-1,dir); updateNav();
  });
  busy=false;
}
/* barra de progreso: una monedita por sub-paso */
const prog=document.getElementById('prog');
const units=[]; slides.forEach((s,i)=>{ for(let k=mn(s);k<=mx(s);k++) units.push([i,k]); });
units.forEach(()=>{ const d=document.createElement('i'); d.className='pc'; prog.appendChild(d); });
function updateNav(){
  const idx=units.findIndex(u=>u[0]===cur&&u[1]===step);
  [...prog.children].forEach((d,i)=>{ d.className='pc'+(i<idx?' done':'')+(i===idx?' cur':''); });
}
/* transición pixelada */
const wipeEl=document.getElementById('wipe'); const cells=[];
const WC=['#ff7fc0','#94ddc6','#f6d36b','#8aa24a','#1a1a1a'];
for(let y=0;y<9;y++)for(let x=0;x<16;x++){ const c=document.createElement('i'); c.style.background=WC[(x*3+y*5)%WC.length]; c._d=(x+y); wipeEl.appendChild(c); cells.push(c); }
async function wipe(mid){
  if(reduce){ mid(); return; }
  const run=(from,to)=>Promise.all(cells.map(c=>c.animate([{transform:`scale(${from})`},{transform:`scale(${to})`}],{duration:200,delay:c._d*13,fill:'forwards',easing:'steps(4)'}).finished));
  await run(0,1.02); mid(); await new Promise(r=>setTimeout(r,60)); await run(1.02,0);
}
document.getElementById('bnext').onclick=e=>{e.currentTarget.blur();next();};
document.getElementById('bprev').onclick=e=>{e.currentTarget.blur();prev();};
addEventListener('keydown',e=>{
  const t=e.target; const inCtl=t.closest&&t.closest('button,input,label');
  if(e.key==='ArrowRight'||e.key==='PageDown'||(!inCtl&&(e.key===' '||e.key==='Enter'))){ e.preventDefault(); next(); }
  else if(e.key==='ArrowLeft'||e.key==='PageUp'){ e.preventDefault(); prev(); }
  else if(e.key==='f'||e.key==='F'){ document.fullscreenElement?document.exitFullscreen():document.documentElement.requestFullscreen?.(); }
});
let tx=null; addEventListener('touchstart',e=>{tx=e.touches[0].clientX},{passive:true});
addEventListener('touchend',e=>{ if(tx==null)return; const d=e.changedTouches[0].clientX-tx; if(Math.abs(d)>60){ d<0?next():prev(); } tx=null; });

/* efectos: lluvia de monedas */
function burst(x,y,n=12){
  const fx=document.getElementById('fx');
  for(let i=0;i<n;i++){
    const im=new Image(); im.src=px(ART.coin,3); im.className='spr'; im.style.cssText=`position:absolute;left:${x}px;top:${y}px;width:24px;image-rendering:pixelated`;
    fx.appendChild(im);
    const dx=(Math.random()-.5)*340, up=-(120+Math.random()*160), dn=140+Math.random()*100;
    im.animate([{transform:'translate(0,0)',opacity:1},{transform:`translate(${dx*.6}px,${up}px)`,opacity:1,offset:.45},{transform:`translate(${dx}px,${dn}px)`,opacity:0}],{duration:1100+Math.random()*400,easing:'ease-out'}).finished.then(()=>im.remove());
  }
}
function countTo(el,n,ms=900){
  if(reduce){ el.textContent=n; return; }
  const t0=performance.now(); (function f(t){ const k=Math.min(1,(t-t0)/ms); el.textContent=Math.round(n*(1-Math.pow(1-k,3))); if(k<1) requestAnimationFrame(f); })(t0);
}
