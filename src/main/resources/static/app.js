'use strict';
const $ = id => document.getElementById(id);
let selected = localStorage.getItem('novamarket.activity'), activities = [], previous = null, busy = false, poll = 0;
function clearPrevious() { previous=null;poll++;$('retry').disabled=true;$('claim-result').textContent='领取结果将显示在这里。'; }
const labels = {SUCCESS:'权益已发放',PROCESSING:'领取已受理，等待发奖',SOLD_OUT:'活动库存已领完',LIMIT_EXCEEDED:'已达到本活动限领数量',ACTIVITY_NOT_RUNNING:'活动已结束',IDEMPOTENCY_CONFLICT:'幂等键与其他用户或活动冲突',RATE_LIMITED:'请求频繁，请稍后重试',INTERNAL_ERROR:'领取失败，请查询状态',STORAGE_ERROR:'存储暂不可用，等待恢复'};
async function api(url, data, requestId) {
  const headers = {'Content-Type':'application/json'};
  if(requestId) headers['X-Request-Id']=requestId;
  const response = await fetch(url, data === undefined ? {} : {method:'POST',headers,body:JSON.stringify(data)});
  const result = await response.json();
  if(!response.ok && response.status !== 202 && !labels[result.code]) throw new Error(result.message || labels[result.code] || `服务响应 ${response.status}`);
  return result;
}
function toast(message) { $('toast').textContent=message; $('toast').style.display='block'; clearTimeout(toast.timer); toast.timer=setTimeout(()=>$('toast').style.display='none',4500); }
function guarded(fn) { return async event => { try { await fn(event); } catch(error) { toast(error.message); } }; }
function renderActivities() {
  const list=$('activities'); list.replaceChildren(); $('activity-count').textContent=activities.length;
  if(!activities.length) { const p=document.createElement('p');p.className='empty';p.textContent='还没有活动。先创建一个。';list.append(p); }
  for(const a of activities) {
    const button=document.createElement('button');button.className='activity-item'+(a.activityId===selected?' active':'');
    const strong=document.createElement('strong');strong.textContent=a.name;
    const small=document.createElement('small');small.textContent=`剩余 ${a.dbStock} · 已发放 ${a.successClaims} · 每人 ${a.perUserLimit} 份`;
    button.append(strong,small);button.onclick=()=>{selected=a.activityId;clearPrevious();localStorage.setItem('novamarket.activity',selected);renderActivities();renderSelected();};list.append(button);
  }
}
function renderSelected() {
  const a=activities.find(x=>x.activityId===selected);
  $('claim-button').disabled=!a || busy;
  $('selected-name').textContent=a?a.name:'选择一个活动';
  $('selected-detail').textContent=a?`每人限领 ${a.perUserLimit} 份 · 总库存 ${a.initTotalStock}`:'从左侧选择，或创建一个新活动。';
  $('activity-status').textContent=a?(a.status==='RUNNING'?'进行中':a.status):'待选择';
  $('available').textContent=a?a.dbStock:'—';$('granted').textContent=a?a.successClaims:'—';$('difference').textContent=a?a.cacheStock-a.dbStock:'—';
}
async function refresh() {
  [activities,metrics]=await Promise.all([api('/api/activities'),api('/api/metrics')]);
  if(!activities.some(a=>a.activityId===selected)) selected=activities[0]?.activityId;
  renderActivities();renderSelected();
  $('pending').textContent=(metrics.messages.PENDING||0)+(metrics.messages.SENT||0);
  $('connection').textContent='服务已连接';$('runtime').textContent=`${metrics.stockImpl==='redis-lua'?'Redis 库存':'内存库存'} · 单机实例`;
}
let metrics;
function showResult(result, context) {
  const box=$('claim-result');box.replaceChildren(); const title=document.createElement('strong');title.className=result.code==='SUCCESS'?'success':'pending';title.textContent=labels[result.code]||result.code;box.append(title);
  const detail=document.createElement('div');detail.textContent=result.claimNo?`领取单：${result.claimNo}`:(result.detail||'');box.append(detail);
  const key=document.createElement('div');key.className='muted';key.textContent=`用户 ${context.userId} · 请求 ${context.requestId}`;box.append(key);
}
async function waitForGrant(result, context, generation) {
  if(!result.claimNo || result.code !== 'PROCESSING') return;
  for(let i=0;i<40 && generation===poll;i++) {
    await new Promise(r=>setTimeout(r,500));const status=await api(`/api/claims/${encodeURIComponent(result.claimNo)}`);
    if(status.status==='SUCCESS') {showResult({...result,code:'SUCCESS'},context);await refresh();return;}
    if(status.status==='ROLLED_BACK'||status.status==='FAILED') {showResult({...result,code:'INTERNAL_ERROR'},context);return;}
  }
}
async function submitClaim(context) {
  if(busy) return;busy=true;renderSelected();$('retry').disabled=true;
  try {
    previous=context;const generation=++poll;
    const result=await api(`/api/activities/${encodeURIComponent(context.activityId)}/claim`,{userId:context.userId},context.requestId);
    showResult(result,context);await refresh();await waitForGrant(result,context,generation);
  } finally {busy=false;renderSelected();$('retry').disabled=!previous;}
}
$('create').onsubmit=guarded(async e=>{e.preventDefault();const form=e.currentTarget; const button=form.querySelector('button');button.disabled=true;
  try{const data=Object.fromEntries(new FormData(form));['totalStock','perUserLimit','ttlSeconds'].forEach(k=>data[k]=Number(data[k]));const a=await api('/api/activities',data);selected=a.activityId;clearPrevious();localStorage.setItem('novamarket.activity',selected);await refresh();toast('活动已创建');}finally{button.disabled=false;}});
$('claim').onsubmit=guarded(async e=>{e.preventDefault();if(selected) await submitClaim({activityId:selected,userId:$('user-id').value,requestId:crypto.randomUUID()});});
$('retry').onclick=guarded(()=>previous&&submitClaim(previous));
$('refresh').onclick=guarded(refresh);
$('fault').onclick=guarded(async()=>{const r=await api('/api/demo/message-failure',{});toast(r.message);});
$('compensate').onclick=guarded(async()=>{await api('/api/compensate/run',{});await refresh();toast('补偿已运行');});
$('concurrency').onclick=guarded(async()=>{
  const button=$('concurrency');button.disabled=true;$('scenario-result').textContent='正在创建活动并发起 200 次请求…';
  try{
    const a=await api('/api/activities',{name:`并发验证 ${new Date().toLocaleTimeString()}`,totalStock:100,perUserLimit:1,ttlSeconds:86400});selected=a.activityId;clearPrevious();localStorage.setItem('novamarket.activity',selected);
    let next=0,accepted=0,soldOut=0,other=0;
    await Promise.all(Array.from({length:16},async()=>{while(next<200){const i=next++;const r=await api(`/api/activities/${a.activityId}/claim`,{userId:`concurrent-${i}`},crypto.randomUUID());if(r.code==='SUCCESS'||r.code==='PROCESSING')accepted++;else if(r.code==='SOLD_OUT')soldOut++;else other++;}}));
    for(let i=0;i<20;i++){await refresh();if(activities.find(x=>x.activityId===selected)?.successClaims===accepted)break;await new Promise(r=>setTimeout(r,250));}
    const current=activities.find(x=>x.activityId===selected);
    $('scenario-result').textContent=`200 次请求：受理 ${accepted}、售罄 ${soldOut}、其他 ${other}；已发放 ${current.successClaims}，库存差额 ${current.cacheStock-current.dbStock}。`;
  }finally{button.disabled=false;}
});
guarded(refresh)();
setInterval(()=>refresh().catch(()=>{$('connection').textContent='服务未连接';}),5000);
