#!/usr/bin/env node
// Fixed request-count benchmark. Full-run numerator AND denominator include all requests.
import {performance} from 'node:perf_hooks';
import {randomUUID} from 'node:crypto';
import {writeFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';

const options={url:'http://127.0.0.1:8082',requests:1000,concurrency:32,stock:100};
for(let i=2;i<process.argv.length;i+=2) {
  const key=process.argv[i].replace(/^--/,'');
  if(!['url','requests','concurrency','stock','out'].includes(key)) throw new Error('Unknown option: '+key);
  options[key]=['requests','concurrency','stock'].includes(key)?Number(process.argv[i+1]):process.argv[i+1];
}
for(const key of ['requests','concurrency','stock']) if(!Number.isInteger(options[key])||options[key]<1)throw new Error(key+' must be a positive integer');
if(options.requests>100000 || options.concurrency>64 || options.stock>1000000)throw new Error('Maximum: requests=100000, concurrency=64, stock=1000000');
const request=async (endpoint,body,key)=>{
  const headers={'Content-Type':'application/json'};if(key)headers['X-Request-Id']=key;
  const response=await fetch(options.url+endpoint,{...(body===undefined?{}:{method:'POST',headers,body:JSON.stringify(body)}),signal:AbortSignal.timeout(10000)});
  return {http:response.status,body:await response.json()};
};
const activity=(await request('/api/activities',{name:'实机验证 '+new Date().toISOString(),totalStock:options.stock,perUserLimit:1,ttlSeconds:86400})).body;
if(!activity.activityId)throw new Error('Activity creation failed: '+JSON.stringify(activity));
const codes={},httpCodes={},latencies=[],errors={};let next=0,accepted=0;
const start=performance.now();
await Promise.all(Array.from({length:options.concurrency},async()=>{
  while(next<options.requests) {
    const seq=next++,at=performance.now();
    try {
      const result=await request('/api/activities/'+activity.activityId+'/claim',{userId:'bench-'+seq},randomUUID());
      codes[result.body.code]=(codes[result.body.code]||0)+1;httpCodes[result.http]=(httpCodes[result.http]||0)+1;
      if(result.http<300&&['SUCCESS','PROCESSING'].includes(result.body.code))accepted++;
      if(result.http>=500)errors['HTTP_'+result.http]=(errors['HTTP_'+result.http]||0)+1;
    }catch(e){errors[e.name]=(errors[e.name]||0)+1;}
    finally{latencies.push(performance.now()-at);}
  }
}));
const elapsedSeconds=(performance.now()-start)/1000;
latencies.sort((a,b)=>a-b);const p=q=>latencies[Math.max(0,Math.ceil(q*latencies.length)-1)];
let detail;
for(let i=0;i<100;i++) {
  detail=(await request('/api/activities/'+activity.activityId)).body;
  if(detail.successClaims===accepted)break;
  await new Promise(r=>setTimeout(r,100));
}
const metrics=(await request('/api/metrics')).body;
const valid=detail.successClaims===accepted&&detail.dbStock===options.stock-accepted&&detail.cacheStock===detail.dbStock;
const report={createdAt:new Date().toISOString(),type:'fixed-count-local-benchmark',config:options,
  environment:{node:process.version,os:os.platform()+' '+os.release(),cpus:os.cpus().length,stockImpl:metrics.stockImpl,messageImpl:'in-process-bus',sameMachineClientAndServer:true},
  requests:options.requests,accepted,grantedAfterDrain:detail.successClaims,codes,httpCodes,systemErrors:errors,
  elapsedSeconds,requestRps:options.requests/elapsedSeconds,acceptedRps:accepted/elapsedSeconds,
  latencyMs:{p50:p(.5),p95:p(.95),p99:p(.99),max:latencies.at(-1)},
  accounting:{...detail,valid},note:'Full run only; no ramp subtraction. Accepted is not granted. Sold-out requests count toward requestRps, not business completion. Not production capacity.'};
const output=path.resolve(options.out||'reports/local-benchmark.json');await mkdir(path.dirname(output),{recursive:true});await writeFile(output,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
if(!valid||Object.values(errors).some(n=>n>0))process.exitCode=1;
