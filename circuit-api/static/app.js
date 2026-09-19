const $ = id => document.getElementById(id);
let board, kit, current, busy = false;
const names = {led:'Red LED', resistor:'Resistor', button:'Pushbutton', power_supply:'Power supply', jumper_wire:'Jumper wires'};
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
  ['generate','analyze','demo-button','demo-led','latest'].forEach(id=>$(id).disabled=value || !kit);
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
function draw(placement) {
  if (!board) return;
  const used = (placement?.components.flatMap(c => c.terminals.map(t => t.position)) || [])
    .concat(placement?.jumperWires.flatMap(w => [w.startPosition, w.endPosition]) || []);
  const full = $('board-view')?.value === 'full';
  const pitch = .00254;
  const minRow = full || !used.length ? 1 : Math.max(1, Math.floor(Math.min(...used.map(p => p.z)) / pitch) - 1);
  const maxRow = full ? 63 : used.length ? Math.min(63, Math.ceil(Math.max(...used.map(p => p.z)) / pitch) + 3) : 20;
  const visible = board.holes.filter(h => h.position.z >= (minRow-1)*pitch - 1e-8 && h.position.z <= (maxRow-1)*pitch + 1e-8);
  const minX = Math.min(...visible.map(h => h.position.x));
  const maxX = Math.max(...visible.map(h => h.position.x));
  const scale = 10000, margin = 38;
  // Rows run horizontally. Every visual uses this one board-local transform.
  const pt = p => [margin + (p.z-(minRow-1)*pitch)*scale, margin + (p.x-minX)*scale];
  const width = (maxRow-minRow)*pitch*scale + margin*2;
  const height = (maxX-minX)*scale + margin*2;
  const svg = svgNode('svg', {viewBox:`0 0 ${width} ${height}`,role:'img','aria-label':`Breadboard rows ${minRow} through ${maxRow}. Row numbers run right; columns run down.`});
  svg.append(svgNode('rect', {x:10,y:10,width:width-20,height:height-20,rx:15,fill:'#f2f1e9',stroke:'#d6dace','stroke-width':2}));
  const gapTop = pt(board.holes.find(h=>h.id==='E1').position)[1]+12;
  const gapBottom = pt(board.holes.find(h=>h.id==='F1').position)[1]-12;
  svg.append(svgNode('rect',{x:22,y:gapTop,width:width-44,height:gapBottom-gapTop,rx:5,fill:'#e2e5da'}));
  const occupied = new Set(placement?.components.flatMap(c=>c.terminals.map(t=>t.holeId)).concat(placement.jumperWires.flatMap(w=>[w.fromHole,w.toHole])) || []);
  for (const h of visible) {
    const [x,y]=pt(h.position);
    const circle=svgNode('circle',{cx:x,cy:y,r:occupied.has(h.id)?4.3:3.4,fill:occupied.has(h.id)?'#456647':'#bac2b1'});
    circle.append(svgNode('title',{},h.id));svg.append(circle);
    if(h.id[0]==='A' && /^A\d+$/.test(h.id) && (!full || Number(h.id.slice(1))%5===0 || h.id==='A1'))
      svg.append(svgNode('text',{x,y:20,'text-anchor':'middle','font-size':10,fill:'#68745f'},h.id.slice(1)));
    if(h.id.match(new RegExp(`^[A-J]${minRow}$`)))
      svg.append(svgNode('text',{x:20,y:y+3,'text-anchor':'middle','font-size':10,fill:'#68745f'},h.id[0]));
  }
  for(const w of placement?.jumperWires || []) {
    const a=pt(w.startPosition), b=pt(w.endPosition);
    const bend=Math.min(45,Math.hypot(b[0]-a[0],b[1]-a[1])*.18);
    svg.append(svgNode('path',{d:`M ${a} Q ${(a[0]+b[0])/2} ${(a[1]+b[1])/2-bend} ${b}`,fill:'none',stroke:colors[w.color]||'#52645c','stroke-width':4,'stroke-linecap':'round',opacity:.8}));
    for(const p of [a,b]) svg.append(svgNode('circle',{cx:p[0],cy:p[1],r:4,fill:colors[w.color]||'#52645c',stroke:'#fff','stroke-width':1.5}));
  }
  for(const c of placement?.components || []) {
    const color=colors[c.type], [x,y]=pt(c.position), points=c.terminals.map(t=>pt(t.position));
    const a=points[0], b=points[1];
    if(c.type==='button') {
      const x0=Math.min(...points.map(p=>p[0])),y0=Math.min(...points.map(p=>p[1]));
      const x1=Math.max(...points.map(p=>p[0])),y1=Math.max(...points.map(p=>p[1]));
      svg.append(svgNode('rect',{x:x0-5,y:y0-5,width:x1-x0+10,height:y1-y0+10,rx:6,fill:'#d8e8e6',stroke:color,'stroke-width':2}));
      svg.append(svgNode('circle',{cx:x,cy:y,r:Math.min(x1-x0,y1-y0)*.25,fill:color}));
    } else if(c.type!=='power_supply') {
      svg.append(svgNode('line',{x1:a[0],y1:a[1],x2:b[0],y2:b[1],stroke:color,'stroke-width':3}));
      if(c.type==='led') svg.append(svgNode('circle',{cx:x,cy:y,r:10,fill:color,stroke:'#fff','stroke-width':2}));
      else svg.append(svgNode('rect',{x:x-14,y:y-7,width:28,height:14,rx:4,fill:'#ddc780',stroke:color,'stroke-width':2}));
    }
    c.terminals.forEach((t,i)=>{
      const dot=svgNode('circle',{cx:points[i][0],cy:points[i][1],r:4,fill:color,stroke:'#fff','stroke-width':1.5});
      dot.append(svgNode('title',{},`${c.id}: ${t.id} → ${t.holeId}`));svg.append(dot);
    });
  }
  $('preview').replaceChildren(svg);
  $('board-caption').textContent=`Rows ${minRow}–${maxRow} · ${full?'full board':'circuit view'}`;
}
function renderParts(parts) {
  const list=document.createElement('div'); list.className='parts-list';
  parts.forEach(p=>{const el=document.createElement('span');el.className='part-chip';el.textContent=`${p.quantity??1} × ${names[p.type]||p.type} · ${p.value}`;list.append(el);});
  $('parts').replaceChildren(list);
}
function render(placement) {
  current=placement;
  $('source').textContent=placement.source==='fixture'?'Demo · validated':`${placement.source==='openrouter'?'OpenRouter':'OpenAI'} · validated`;
  renderParts(placement.requiredParts);draw(placement);
  const title=document.createElement('h3');title.textContent='Assembly sequence';
  const list=document.createElement('ol');
  placement.instructions.forEach(i=>{const li=document.createElement('li'),n=document.createElement('b'),t=document.createElement('span');n.textContent=String(i.step).padStart(2,'0');t.textContent=i.text;li.append(n,t);list.append(li);});
  $('steps').replaceChildren(title,list);$('json').textContent=JSON.stringify(placement,null,2);
  $('download').disabled=false;$('copy').disabled=false;
  status(`${placement.title} · ${placement.components.length} components · ${placement.jumperWires.length} jumpers. Layout checks passed. Physical fit still needs confirmation.`);
}
async function run(path, partsOnly=false) {
  if(busy) return;
  try {
    const body=request();setBusy(true);
    status(partsOnly?'Identifying the required components…':'Identifying parts, assigning holes, and checking the circuit…');
    const result=await api(path,body);
    if(partsOnly) {
      current=null;renderParts(result.components);$('steps').replaceChildren();draw();
      $('source').textContent='Parts identified';$('json').textContent=JSON.stringify(result,null,2);
      status(result.explanation+' Generate the circuit to assign holes.');
    } else { if(result.source==='fixture') $('prompt').value=result.prompt; render(result); }
  } catch(error) {status(error.message+(current?'\nThe preview still shows the last valid circuit.':''),true);}
  finally {setBusy(false);}
}
$('generate').onclick=()=>run('/api/circuits/generate');
$('analyze').onclick=()=>run('/api/circuits/analyze',true);
$('demo-button').onclick=()=>run('/api/circuits/demo/button_led');
$('demo-led').onclick=()=>run('/api/circuits/demo/led');
$('latest').onclick=async()=>{try{const id=$('session').value;if(!/^[A-Za-z0-9_-]{1,64}$/.test(id))throw new Error('Enter a valid session ID.');setBusy(true);render(await api(`/api/sessions/${encodeURIComponent(id)}/placement`));}catch(e){status(e.message,true);}finally{setBusy(false);}};
$('download').onclick=()=>{const url=URL.createObjectURL(new Blob([JSON.stringify(current,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='placement.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
$('copy').onclick=async()=>{try{await navigator.clipboard.writeText(JSON.stringify(current,null,2));status('Placement JSON copied.');}catch{status('Clipboard is unavailable here. Download placement.json instead.',true);}};
async function init(){try{
  const [health,loadedKit]=await Promise.all([api('/api/health'),api('/api/kit')]);kit=loadedKit;
  board=await api('/api/breadboards/'+kit.breadboardModel);
  $('connection').textContent=health.liveConfigured?`● ${health.provider === 'openrouter' ? 'OpenRouter' : 'OpenAI'} connected`:'● Offline mode · add an API key';
  const option=document.createElement('option');option.value=kit.breadboardModel;option.textContent='830-hole breadboard';option.title=kit.breadboardModel;$('board').replaceChildren(option);
  kit.availableParts.forEach((p,i)=>{const row=document.createElement('div');row.className='part-row';const label=document.createElement('label');label.htmlFor='qty-'+i;label.textContent=names[p.type];const sub=document.createElement('small');sub.textContent=p.value;label.append(sub);const input=document.createElement('input');input.id='qty-'+i;input.type='number';input.min=0;input.max=30;input.value=p.quantity;row.append(label,input);$('inventory').append(row);});
  draw();setBusy(false);
}catch(e){status('Could not load the board: '+e.message,true);$('connection').textContent='Connection unavailable';}}
$('board-view').onchange=()=>draw(current);
init();
