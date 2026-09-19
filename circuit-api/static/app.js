const $ = id => document.getElementById(id);
let board, kit, current, busy = false, selectedIds = [];
const names = {led:'Red LED', resistor:'Resistor', button:'Pushbutton', power_supply:'5 V supply', arduino_uno:'Arduino Uno R3', jumper_wire:'Jumper wires'};
const colors = {led:'#c7645a', resistor:'#b6953c', button:'#6497a1', power_supply:'#9a6859',red:'#c96559', black:'#52645c', yellow:'#bfa242',blue:'#6497a1',green:'#6d9450'};

async function api(path, data) {
  const response = await fetch(path, data ? {method:'POST', headers:{'Content-Type':'application/json'},body:JSON.stringify(data)} : {});
  const result = await response.json();
  if (!response.ok) throw new Error(result.error?.message + (result.error?.details?.length ? '\n' + JSON.stringify(result.error.details) : ''));
  return result;
}
function status(text, error=false) { $('status').textContent=text; $('status').className=error?'error':''; }
function setBusy(value) {
  busy=value;
  ['generate','analyze','demo-button','demo-led','demo-arduino','latest'].forEach(id=>$(id).disabled=value || !kit);
  document.querySelectorAll('input,textarea,select').forEach(el=>el.disabled=value);
  $('download').disabled=busy || !current; $('copy').disabled=busy || !current;
}
function request() {
  if (!$('session').checkValidity() || !$('session').value) throw new Error('Session ID must contain 1–64 letters, numbers, underscores or hyphens.');
  if ($('prompt').value.trim().length<3) throw new Error('Describe the circuit first.');
  return {prompt:$('prompt').value, sessionId:$('session').value,breadboardModel:$('board').value,
    availableParts:kit.availableParts.map((p,i)=>({...p,quantity:Number($('qty-'+i).value)}))};
}
function svgNode(tag, attrs={}, text) {
  const el=document.createElementNS('http://www.w3.org/2000/svg',tag);
  Object.entries(attrs).forEach(([k,v])=>el.setAttribute(k,v));
  if(text!==undefined) el.textContent=text;
  return el;
}
const pinNames = {positive:'+5V (+)', negative:'GND (−)', anode:'Anode (+, long leg)', cathode:'Cathode (−, short leg)', a:'Lead A', b:'Lead B', a1:'A1 · paired with A2', a2:'A2 · paired with A1', b1:'B1 · paired with B2', b2:'B2 · paired with B1'};
function focusItems(ids) {
  selectedIds=ids; draw(current);
  document.querySelectorAll('[data-item-ids]').forEach(el=>el.classList.toggle('selected',JSON.parse(el.dataset.itemIds).some(id=>ids.includes(id))));
}
function draw(placement) {
  if (!board) return;
  const external=placement?.externalConnections || [];
  const used=(placement?.components.flatMap(c=>c.terminals.map(t=>t.position)) || [])
    .concat(placement?.jumperWires.flatMap(w=>[w.startPosition,w.endPosition]) || [], external.map(c=>c.boardPosition));
  const full=$('board-view').value==='full', pitch=.00254;
  const minRow=full || !used.length?1:Math.max(1,Math.floor(Math.min(...used.map(p=>p.z))/pitch)-1);
  const maxRow=full?63:used.length?Math.min(63,Math.ceil(Math.max(...used.map(p=>p.z))/pitch)+3):20;
  const inRows=h=>h.position.z>=(minRow-1)*pitch-1e-8 && h.position.z<=(maxRow-1)*pitch+1e-8;
  const visible=board.holes.filter(inRows);
  // Width always spans the whole board (outer rail to outer rail) so the view does not jump between row ranges.
  const minX=Math.min(...board.holes.map(h=>h.position.x)), maxX=Math.max(...board.holes.map(h=>h.position.x));
  const scale=7500, margin=40, boardWidth=(maxX-minX)*scale+2*margin;
  const height=(maxRow-minRow)*pitch*scale+2*margin;
  // One uniform scale preserves the actual reference hole geometry in both axes.
  const pt=p=>[margin+(p.x-minX)*scale, margin+(p.z-(minRow-1)*pitch)*scale];
  const hole=id=>board.holes.find(h=>h.id===id);
  const svg=svgNode('svg',{viewBox:`0 0 ${boardWidth+200} ${height}`,role:'img','aria-label':`Breadboard, rows ${minRow} to ${maxRow}`});
  svg.append(svgNode('rect',{x:8,y:8,width:boardWidth-16,height:height-16,rx:12,fill:'#faf9f3',stroke:'#d4d8cf'}));
  const gapLeft=pt(hole('E1').position)[0]+9;
  const gapRight=pt(hole('F1').position)[0]-9;
  svg.append(svgNode('rect',{x:gapLeft,y:24,width:gapRight-gapLeft,height:height-48,rx:5,fill:'#e4e7df'}));
  // Power rails: a coloured stripe per rail segment behind its holes, plus a polarity mark at the top.
  for(const prefix of ['L+','L-','R+','R-']) {
    const positive=prefix.includes('+'),stripe=positive?'#d9a7a0':'#a7c0d0';
    for(const segment of ['A','B']) {
      const ends=visible.filter(h=>h.net===prefix+segment);
      if(!ends.length)continue;
      const [x]=pt(ends[0].position),y0=pt(ends[0].position)[1],y1=pt(ends[ends.length-1].position)[1];
      svg.append(svgNode('line',{'data-rail':prefix+segment,x1:x,y1:y0-8,x2:x,y2:y1+8,stroke:stripe,'stroke-width':1.5,'stroke-linecap':'round'}));
    }
    const [x]=pt(hole(prefix+'A1').position);
    svg.append(svgNode('text',{x,y:20,'text-anchor':'middle','font-size':13,'font-weight':700,fill:positive?'#b54339':'#37769b'},positive?'+':'−'));
  }
  const records=[...(placement?.components || []).map(c=>({id:c.id,label:names[c.type],holes:c.terminals.map(t=>t.holeId),point:c.position})),
    ...(placement?.jumperWires || []).map(w=>({id:w.id,label:`${w.fromHole} → ${w.toHole}`,holes:[w.fromHole,w.toHole],point:w.startPosition})),
    ...external.map(c=>({id:c.id,label:`Uno ${c.pin} → ${c.holeId}`,holes:[c.holeId],point:c.boardPosition}))];
  const focused=new Set(records.filter(r=>selectedIds.includes(r.id)).flatMap(r=>r.holes));
  const focusedNets=new Set(board.holes.filter(h=>focused.has(h.id)).map(h=>h.net));
  for(const h of visible) {
    const [x,y]=pt(h.position);
    if(focusedNets.has(h.net)) svg.append(svgNode('circle',{cx:x,cy:y,r:7,fill:'#deefb6'}));
    const rail=/^[LR]/.test(h.id),railFill=h.id[1]==='+'?'#c98a83':'#8aa9bd';
    const dot=svgNode('circle',{'data-hole':h.id,cx:x,cy:y,r:focused.has(h.id)?4.5:3,fill:focused.has(h.id)?'#254b39':rail?railFill:'#b5beb1'});
    dot.append(svgNode('title',{},`${h.id} · ${h.net}`));svg.append(dot);
    if(/^A\d+$/.test(h.id)) svg.append(svgNode('text',{x:16,y:y+3,'font-size':9,fill:'#627263'},h.id.slice(1)));
    if(new RegExp(`^[A-J]${minRow}$`).test(h.id)) svg.append(svgNode('text',{x,y:20,'text-anchor':'middle','font-size':10,fill:'#334e40'},h.id[0]));
  }
  for(const w of placement?.jumperWires || []) {
    const a=pt(w.startPosition),b=pt(w.endPosition),active=!selectedIds.length||selectedIds.includes(w.id);
    svg.append(svgNode('path',{d:`M ${a} Q ${(a[0]+b[0])/2+22} ${(a[1]+b[1])/2} ${b}`,fill:'none',stroke:colors[w.color], 'stroke-width':active?3.5:2,opacity:active?1:.22}));
    for(const p of [a,b])svg.append(svgNode('circle',{cx:p[0],cy:p[1],r:3.5,fill:colors[w.color],stroke:'#fff','stroke-width':1}));
  }
  for(const c of placement?.components || []) {
    const color=colors[c.type],[x,y]=pt(c.position),points=c.terminals.map(t=>pt(t.position)),[a,b]=points;
    const group=svgNode('g',{opacity:!selectedIds.length||selectedIds.includes(c.id)?1:.3});
    if(c.type==='button') {
      const xs=points.map(p=>p[0]),ys=points.map(p=>p[1]);
      group.append(svgNode('rect',{x:Math.min(...xs)-4,y:Math.min(...ys)-4,width:Math.max(...xs)-Math.min(...xs)+8,height:Math.max(...ys)-Math.min(...ys)+8,rx:5,fill:'#d8e8e6',stroke:color}));
      group.append(svgNode('circle',{cx:x,cy:y,r:12,fill:color}));
    } else if(c.type!=='power_supply') {
      group.append(svgNode('line',{x1:a[0],y1:a[1],x2:b[0],y2:b[1],stroke:color,'stroke-width':3}));
      group.append(c.type==='led'?svgNode('circle',{cx:x,cy:y,r:8,fill:color}):svgNode('rect',{x:x-6,y:y-13,width:12,height:26,rx:4,fill:'#e0c77c',stroke:color}));
    }
    c.terminals.forEach((t,i)=>{const dot=svgNode('circle',{cx:points[i][0],cy:points[i][1],r:3.5,fill:color,stroke:'#fff','stroke-width':1});dot.append(svgNode('title',{},`${names[c.type]} · ${pinNames[t.id]} → ${t.holeId}`));group.append(dot);});
    svg.append(group);
  }
  const callouts=records.filter(r=>selectedIds.length?selectedIds.includes(r.id):!placement.jumperWires.some(w=>w.id===r.id)).sort((a,b)=>a.point.z-b.point.z);
  let lastY=15;
  callouts.forEach(r=>{
    const [x,y]=pt(r.point),labelY=Math.max(y,lastY+36);lastY=labelY;
    svg.append(svgNode('path',{d:`M ${x} ${y} L ${boardWidth+3} ${labelY}`,fill:'none',stroke:'#889d83','stroke-width':1,'stroke-dasharray':'3 3'}));
    svg.append(svgNode('text',{x:boardWidth+8,y:labelY-3,'font-size':11,'font-weight':600,fill:'#254b39'},r.label));
    svg.append(svgNode('text',{x:boardWidth+8,y:labelY+12,'font-size':10,fill:'#677762'},r.holes.join(' · ')));
  });
  svg.setAttribute('viewBox',`0 0 ${boardWidth+200} ${Math.max(height,lastY+28)}`);
  $('preview').replaceChildren(svg);
  $('board-caption').textContent=`${full?'Full board':'Circuit detail'} · rows ${minRow}–${maxRow}`;
}
function renderConnections(placement) {
  const rows=[];
  placement.components.forEach(c=>rows.push({ids:[c.id],title:`${names[c.type]} · ${c.value}`,details:c.terminals.map(t=>`${pinNames[t.id]||t.id} → ${t.holeId}`).join('  /  ')}));
  placement.jumperWires.forEach((w,i)=>rows.push({ids:[w.id],title:`Jumper ${i+1} · ${w.color}`,details:`${w.fromHole} → ${w.toHole}`}));
  (placement.externalConnections||[]).forEach(c=>rows.push({ids:[c.id],title:`Arduino Uno · ${c.pin}${c.pin==='GND'?' (−)':' (digital output)'}`,details:`${c.pin} → breadboard ${c.holeId}`}));
  $('connections').replaceChildren(...rows.map(row=>{const button=document.createElement('button');button.className='connection-row';button.dataset.itemIds=JSON.stringify(row.ids);const title=document.createElement('strong'),detail=document.createElement('span');title.textContent=row.title;detail.textContent=row.details;button.append(title,detail);button.onclick=()=>focusItems(row.ids);return button;}));
  $('firmware').hidden=!placement.firmware;
  if(placement.firmware){$('sketch').textContent=placement.firmware.code;$('upload-help').textContent=placement.firmware.uploadInstructions;}
}
function renderParts(parts) {
  const list=document.createElement('div'); list.className='parts-list';
  parts.forEach(p=>{const el=document.createElement('span');el.className='part-chip';el.textContent=`${p.quantity??1} × ${names[p.type]||p.type} · ${p.value}`;list.append(el);});
  $('parts').replaceChildren(list);
}
function render(placement) {
  if(placement.breadboard.holeMapVersion!==board.holeMapVersion) throw new Error('This saved circuit uses an older board map. Generate it again before using the current board in XR.');
  current=placement;selectedIds=[];renderConnections(placement);
  $('source').textContent=placement.source==='fixture'?'Ready-made circuit':'Generated circuit';
  renderParts(placement.requiredParts);draw(placement);
  const title=document.createElement('h3');title.textContent='Assembly sequence';
  const list=document.createElement('ol');
  placement.instructions.forEach(i=>{const li=document.createElement('li'),n=document.createElement('b'),t=document.createElement('span');n.textContent=String(i.step).padStart(2,'0');t.textContent=i.text;const btn=document.createElement("button");btn.dataset.itemIds=JSON.stringify(i.componentIds);btn.onclick=()=>focusItems(i.componentIds);btn.append(n,t);li.append(btn);list.append(li);});
  $('steps').replaceChildren(title,list);$('json').textContent=JSON.stringify(placement,null,2);
  $('download').disabled=false;$('copy').disabled=false;
  status(`${placement.title} · ${placement.components.length+(placement.externalDevices?.length||0)} components · ${placement.jumperWires.length+(placement.externalConnections?.length||0)} jumpers · circuit checks passed.`);
}
async function run(path, partsOnly=false) {
  if(busy) return;
  try {
    const body=request();setBusy(true);
    status(partsOnly?'Identifying the required components…':'Identifying parts, assigning holes, and checking the circuit…');
    const result=await api(path,body);
    if(partsOnly) {
      current=null;renderParts(result.components);$('steps').replaceChildren();$('connections').replaceChildren();$('firmware').hidden=true;selectedIds=[];draw();
      $('source').textContent='Parts identified';$('json').textContent=JSON.stringify(result,null,2);
      status(result.explanation+' Generate the circuit to assign holes.');
    } else { if(result.source==='fixture') $('prompt').value=result.prompt; render(result); }
  } catch(error) {status(error.message+(current?'\nThe layout shown is your previous circuit.':''),true);}
  finally {setBusy(false);}
}
$('generate').onclick=()=>run('/api/circuits/generate');
$('analyze').onclick=()=>run('/api/circuits/analyze',true);
$('demo-button').onclick=()=>run('/api/circuits/demo/button_led');
$('demo-led').onclick=()=>run('/api/circuits/demo/led');
$('demo-arduino').onclick=()=>{const i=kit.availableParts.findIndex(p=>p.type==='arduino_uno');if(Number($('qty-'+i).value)<1)$('qty-'+i).value=1;run('/api/circuits/demo/arduino_led');};
$('clear-focus').onclick=()=>focusItems([]);
$('download-sketch').onclick=()=>{if(!current?.firmware)return;const url=URL.createObjectURL(new Blob([current.firmware.code],{type:'text/plain'}));const a=document.createElement('a');a.href=url;a.download='circuit.ino';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
$('latest').onclick=async()=>{try{const id=$('session').value;if(!/^[A-Za-z0-9_-]{1,64}$/.test(id))throw new Error('Enter a valid session ID.');setBusy(true);render(await api(`/api/sessions/${encodeURIComponent(id)}/placement`));}catch(e){status(e.message,true);}finally{setBusy(false);}};
$('download').onclick=()=>{const url=URL.createObjectURL(new Blob([JSON.stringify(current,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='placement.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
$('copy').onclick=async()=>{try{await navigator.clipboard.writeText(JSON.stringify(current,null,2));status('Placement JSON copied.');}catch{status('Clipboard is unavailable here. Download placement.json instead.',true);}};
async function init(){try{
  const [health,loadedKit]=await Promise.all([api('/api/health'),api('/api/kit')]);kit=loadedKit;kit.availableParts.push({type:"arduino_uno",value:"Uno R3",quantity:0});
  board=await api('/api/breadboards/'+kit.breadboardModel);
  $('connection').textContent=health.liveConfigured?'● Generation ready':'● Ready-made circuits only';
  const option=document.createElement('option');option.value=kit.breadboardModel;option.textContent='830-hole breadboard';option.title=kit.breadboardModel;$('board').replaceChildren(option);
  kit.availableParts.forEach((p,i)=>{const row=document.createElement('div');row.className='part-row';const label=document.createElement('label');label.htmlFor='qty-'+i;label.textContent=names[p.type];const sub=document.createElement('small');sub.textContent=p.value;label.append(sub);const input=document.createElement('input');input.id='qty-'+i;input.type='number';input.min=0;input.max=30;input.value=p.quantity;row.append(label,input);$('inventory').append(row);});
  draw();setBusy(false);
}catch(e){status('Could not reach the server: '+e.message,true);$('connection').textContent='● Server unavailable';}}
$('board-view').onchange=()=>draw(current);
init();
