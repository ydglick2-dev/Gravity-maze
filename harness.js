const { chromium } = require('playwright-core');
const fs = require('fs');

function buildPage() {
  let body = fs.readFileSync('/home/user/Gravity-maze/index.html', 'utf8');
  const hook = `
window.__t={
  st:()=>({running,paused,runMode,runLive,runDead,bossMode,hp:bossHp,maxHp:bossMaxHp,ammo:bossAmmo,supN:bossSupN,
    shots:bossShots.length,remn:bossRemn.length,pshots:bossPShots.length,falling,winPause,levelIdx,
    custom:!!customLv,daily:!!(customLv&&customLv._daily),coins,gems,chests,deaths:lvDeaths,
    shieldT:supShieldT,slowT:supSlowT,mudT:supMudT,stopT:supStopT,rapidT:supRapidT,cine:cineMode,ts:timeScale,
    chestBusy:(typeof chestBusy!=='undefined')?chestBusy:null,unlocked}),
  vis:()=>({ovs:Array.from(document.querySelectorAll('.overlay')).filter(o=>!o.classList.contains('hidden')).map(o=>o.id),
    hud:document.getElementById('hud').style.display,
    fire:!document.getElementById('fireWrap').classList.contains('hidden'),
    sup:(function(){var s=document.getElementById('supBtn');return s?!s.classList.contains('hidden')&&getComputedStyle(s).display!=='none':null;})(),
    box:document.getElementById('boxOv').style.display,
    prize:document.getElementById('prizeStage').style.display,
    boxHint:document.getElementById('boxHint').textContent}),
  unlock:()=>{unlocked=1000;},
  start:(i)=>{document.querySelectorAll('.overlay').forEach(o=>o.classList.add('hidden'));startRun(i);},
  win:()=>{const g=P(L().goal);ball.x=g.x;ball.y=g.y;ball.vx=0;ball.vy=0;},
  park:()=>{ball.x=board.x+.5*board.w;ball.y=board.y+.9*board.h;ball.vx=0;ball.vy=0;},
  under:()=>{ball.x=board.x+bossX*board.w;ball.y=board.y+.72*board.h;ball.vx=0;ball.vy=0;},
  moveTo:(fx,fy)=>{ball.x=board.x+fx*board.w;ball.y=board.y+fy*board.h;ball.vx=0;ball.vy=0;},
  skin:(i)=>{if(!skinOwned.includes(i))skinOwned.push(i);skinSel=i;supUi();},
  charge:()=>{bossSupN=5;supUi();},
  sup:()=>{bossSuper();},
  hit:(n)=>{const G=bossGeo();for(let k=0;k<(n||1);k++)bossApplyHit(G.cx,G.cy,G,true);},
  makeShots:(n)=>{for(let i=0;i<n;i++)bossShots.push({x:board.x+(.2+.1*i)*board.w,y:board.y+.5*board.h,vx:0,vy:20,life:4,r:ball.r*.8,grav:0,rot:0});},
  freezeBoss:()=>{if(bossMode!=='dying'){bossStT=999;bossMode='idle';}bossShots.length=0;},
  ammoSet:(n)=>{bossAmmo=n;bossAmmoUi();},
  give:(c,g2,ch)=>{coins=c;gems=g2;chests=ch;save();refreshTro();},
  remnPos:()=>bossRemn.map(m=>({x:m.x,y:m.y})),
  ballAt:()=>({x:ball.x,y:ball.y,bx:board.x,by:board.y,bw:board.w,bh:board.h}),
  fire:()=>{bossTryFire();},
  die:()=>{bossInvT=0;supShieldT=0;ball.spawnT=0;falling=.0001;fallPt={x:ball.x,y:ball.y};},
  setDeaths:(n)=>{lvDeaths=n;try{updateHud();}catch(e){}},
  scState:()=>({started:scratchStarted,revealed:scratchRevealed,wheelD,free:scratchFree()}),
  negArc:()=>window.__negArc||null,
  ev:(code)=>eval(code)
};initAuth();
`;
  if (!body.includes('initAuth();\n')) throw new Error('anchor not found');
  body = body.replace('initAuth();\n', hook);
  fs.writeFileSync('/home/user/Gravity-maze/test-agent-e2e.html', body);
}

async function launch() {
  buildPage();
  const errors = [];
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--no-sandbox', '--enable-unsafe-swiftshader'] });
  const ctx = await b.newContext({ viewport: { width: 420, height: 800 }, serviceWorkers: 'block' });
  await ctx.route(/textdb\.dev|workers\.dev|peerjs/, r => r.abort());
  const pg = await ctx.newPage();
  pg.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  pg.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE: ' + m.text().slice(0, 300)); });
  await pg.addInitScript(() => { try { localStorage.setItem('mazeBackend', 'off'); } catch (e) { } });
  await pg.goto('http://localhost:8901/test-agent-e2e.html');
  await pg.waitForTimeout(1500);
  await pg.click('#authGuest').catch(() => 0);
  await pg.waitForTimeout(800);
  return { b, pg, errors };
}
module.exports = { launch };
