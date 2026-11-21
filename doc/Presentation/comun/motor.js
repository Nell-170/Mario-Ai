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
  if(!spec) return '';
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
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const stage = document.getElementById('stage');
const slides = [...document.querySelectorAll('.slide')];
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
/* el recorrido sigue la lista de sub-pasos (units), que puede intercalar filminas */
const unitIdx=()=>units.findIndex(u=>u[0]===cur&&u[1]===step);
function goUnit(u,dir){ if(u[0]===cur) setStep(u[1],dir); else goSlide(u[0],dir,u[1]); }
function next(){ if(busy)return; const i=unitIdx(); if(i<units.length-1) goUnit(units[i+1],'fwd'); }
function prev(){ if(busy)return; const i=unitIdx(); if(i>0) goUnit(units[i-1],'back'); }
async function goSlide(n,dir,at){
  busy=true;
  /* data-sin-transicion="id": entre esa filmina y esta no se usa la cortina pixelada */
  const quick=slides[n].dataset.sinTransicion===slides[cur].id||slides[cur].dataset.sinTransicion===slides[n].id;
  const swap=()=>{
    H[slides[cur].id]?.leave?.();
    slides[cur].classList.remove('active'); cur=n; const sl=slides[cur]; sl.classList.add('active');
    step=at??(dir==='fwd'?mn(sl):mx(sl)); sl.dataset.step=step; applyAt(sl,step);
    H[sl.id]?.enter?.(step,dir); H[sl.id]?.step?.(step,-1,dir); updateNav();
  };
  if(quick) swap(); else await wipe(swap);
  busy=false;
}
/* barra de navegación: secciones */
const secNav=document.getElementById('sec-nav');
const SECTIONS=[];
slides.forEach((s,idx)=>{
  const name=s.dataset.sec;
  if(!name) return;
  const existing=SECTIONS.find(sec=>sec.name===name);
  if(!existing) SECTIONS.push({name,firstSlide:idx,lastSlide:idx});
  else existing.lastSlide=idx;
});
if(secNav){
  secNav.innerHTML='';
  SECTIONS.forEach((sec,i)=>{
    if(i>0){
      const sep=document.createElement('span');
      sep.className='ssep';
      sep.textContent='|';
      secNav.appendChild(sep);
    }
    const btn=document.createElement('button');
    btn.type='button';
    btn.className='sitem';
    btn.dataset.sec=sec.name;
    btn.textContent=sec.name;
    btn.title=`Ir a ${sec.name} (filmina ${sec.firstSlide+1})`;
    btn.onclick=(e)=>{
      e.currentTarget.blur();
      if(cur<sec.firstSlide||cur>sec.lastSlide){
        // siempre al primer sub-paso de la sección, se llegue desde adelante o desde atrás
        goSlide(sec.firstSlide, sec.firstSlide>cur?'fwd':'back', mn(slides[sec.firstSlide]));
      }
    };
    secNav.appendChild(btn);
  });
}
/* lista de transiciones totales (una unidad por cada paso de cada filmina) */
const units=[];
slides.forEach((s,i)=>{
  for(let k=mn(s);k<=mx(s);k++) units.push([i,k]);
});
/* data-despues-de="id-de-otra-section:paso" intercala esta filmina justo después
   de ese sub-paso de la otra; al terminarla se vuelve al sub-paso siguiente */
slides.forEach((s,i)=>{
  const d=s.dataset.despuesDe; if(!d) return;
  const [id,st]=d.split(':'), mine=units.filter(u=>u[0]===i);
  const rest=units.filter(u=>u[0]!==i);
  const j=rest.findIndex(u=>slides[u[0]].id===id&&u[1]===+st); if(j<0) return;
  rest.splice(j+1,0,...mine); units.splice(0,units.length,...rest);
});
/* numeración: una por filmina (data-cuenta="paso" hace que cada sub-paso cuente como número propio);
   sigue el orden del recorrido, así que las filminas intercaladas quedan en su lugar */
const numMap={}; let TOTAL=0, _prev=-1;
units.forEach(u=>{ if(u[0]!==_prev||slides[u[0]].dataset.cuenta==='paso') TOTAL++; _prev=u[0]; numMap[u[0]+':'+u[1]]=TOTAL; });
const numOf=(i,st)=>numMap[i+':'+st];
const curNum=()=>numOf(cur,step);
const unitOfNum=n=>units.findIndex(u=>numOf(u[0],u[1])===n);
function updateNav(){
  const isCover = cur === 0;
  if(secNav){
    secNav.style.display = isCover ? 'none' : 'flex';
    const currentSecName = slides[cur]?.dataset.sec || '';
    secNav.querySelectorAll('.sitem').forEach(btn => {
      btn.classList.toggle('on', btn.dataset.sec === currentSecName);
    });
  }
  const snEl = document.getElementById('slide-num');
  if(snEl){
    snEl.style.display = isCover ? 'none' : 'flex';
  }
  const bprev = document.getElementById('bprev');
  if(bprev){
    bprev.style.display = isCover ? 'none' : '';
  }
  const snCur = document.getElementById('sn-cur');
  const snTot = document.getElementById('sn-tot');
  if(snCur) snCur.textContent = String(curNum());
  if(snTot) snTot.textContent = String(TOTAL);
}
const snEl=document.getElementById('slide-num');
if(snEl){
  snEl.onclick=()=>{
    const val=prompt(`Ir a diapositiva (1 a ${TOTAL}):`, curNum());
    const n=parseInt(val,10);
    if(!isNaN(n)&&n>=1&&n<=TOTAL&&!busy){
      const i=unitIdx(), k=unitOfNum(n);
      if(k>=0&&k!==i) goUnit(units[k], k>i?'fwd':'back');
    }
  };
}
/* transición pixelada */
const wipeEl=document.getElementById('wipe'); const cells=[];
const WC=['#ff7fc0','#94ddc6','#f6d36b','#8aa24a','#1a1a1a'];
for(let y=0;y<9;y++)for(let x=0;x<16;x++){ const c=document.createElement('i'); c.style.background=WC[(x*3+y*5)%WC.length]; c._d=(x+y); wipeEl.appendChild(c); cells.push(c); }
async function wipe(mid){
  if(reduce){ mid(); return; }
  /* Safari (WebKit/Apple Silicon) deja celdas "pegadas" cuando una animación con
     fill:'forwards' termina en scale(0) sobre capas compuestas: por eso la
     cortina solo existe mientras corre, y al terminar se cancelan las
     animaciones y se oculta del todo (visibility:hidden). */
  wipeEl.style.visibility='visible';
  const anims=[];
  const run=(from,to)=>Promise.all(cells.map(c=>{
    const a=c.animate([{transform:`scale(${from})`},{transform:`scale(${to})`}],{duration:200,delay:c._d*13,fill:'both',easing:'steps(4)'});
    anims.push(a); return a.finished;
  }));
  try{
    await run(0,1.02); mid(); await new Promise(r=>setTimeout(r,60)); await run(1.02,0);
  } finally {
    anims.forEach(a=>a.cancel());
    wipeEl.style.visibility='hidden';
  }
}
document.getElementById('bnext').onclick=e=>{e.currentTarget.blur();next();};
document.getElementById('bprev').onclick=e=>{e.currentTarget.blur();prev();};
/* teclas numéricas: van al número del contador (n / total). Se pueden teclear
   varios dígitos seguidos (2, 7 → 27); salta tras una pausa corta o con Enter. */
let numBuf='', numTimer=0;
function jumpBuf(){
  clearTimeout(numTimer); const n=parseInt(numBuf,10); numBuf=''; updateNav();
  if(busy||isNaN(n)) return;
  const k=unitOfNum(Math.min(Math.max(n,1),TOTAL)), i=unitIdx();
  if(k>=0&&k!==i) goUnit(units[k], k>i?'fwd':'back');
}
addEventListener('keydown',e=>{
  const t=e.target; const inCtl=t.closest&&t.closest('button,input,label');
  if(!inCtl && /^[0-9]$/.test(e.key)){
    e.preventDefault(); numBuf=(numBuf+e.key).slice(-String(TOTAL).length);
    const sc=document.getElementById('sn-cur'); if(sc) sc.textContent=numBuf;   // muestra lo que se va tecleando
    clearTimeout(numTimer); numTimer=setTimeout(jumpBuf,650);
  }
  else if(numBuf && e.key==='Enter'){ e.preventDefault(); jumpBuf(); }
  else if(e.key==='ArrowRight'||e.key==='PageDown'||(!inCtl&&(e.key===' '||e.key==='Enter'))){ e.preventDefault(); next(); }
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
