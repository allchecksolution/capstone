import {makeXlsx} from './exporter.js';
import {FLOW_CATALOG} from './flow-catalog.js';
import * as Admin from './operations.js';
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
const source=await readFile(new URL('./app.js',import.meta.url),'utf8');
function setup(){const storage=new Map();const listeners={};const app={innerHTML:'',addEventListener(type,handler){const previous=listeners[type];listeners[type]=e=>{previous?.(e);handler(e)}}};const toast={style:{}};const error={};const context=vm.createContext({document:{querySelector:s=>s==='#app'?app:s==='#toast'?toast:error},localStorage:{getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v)},location:{hash:''},window:{addEventListener(){},scrollTo(){}},matchMedia:()=>({matches:false}),setTimeout:()=>0,clearTimeout(){},console,Date,URL,Blob,Admin,FLOW_CATALOG,makeXlsx,structuredClone,crypto});vm.runInContext(source.split('\n').filter(line=>!line.startsWith('import ')).join('\n'),context);return {context,app,storage,listeners,run:code=>vm.runInContext(code,context)}}
test('fresh state requires login and starts with zero jobs',()=>{const s=setup();assert.match(s.app.innerHTML,/관리자 로그인/);assert.equal(s.run('jobs.length'),0);s.run("signedIn=true;pendingUser='tester';home()");assert.match(s.app.innerHTML,/아직 등록된 의뢰가 없어요/);assert.match(s.app.innerHTML,/<strong>0<span>건/)});
test('registered job persists and appears in detail, filters and statistics',()=>{const s=setup();s.run("signedIn=true;pendingUser='tester';jobs.push({id:'TEST-1',customer:'<고객>',phone:'010-0000-0000',type:'누수',address:'테스트 주소',symptom:'점검',created:new Date().toISOString(),status:'배차 대기'});save();home()");assert.match(s.app.innerHTML,/<strong>1<span>건/);assert.equal(JSON.parse(s.storage.get('allcheck-admin-prototype-v1')).length,1);s.run("detail('TEST-1')");assert.match(s.app.innerHTML,/&lt;고객&gt;/);assert.match(s.app.innerHTML,/미배정/);assert.equal(s.run("status='작업 완료';filtered().length"),0);assert.equal(s.run("status='';search='테스트 주소';filtered().length"),1);assert.equal(s.run("search='없는고객';filtered().length"),0)});
test('every main route renders and logout does not expose records',()=>{const s=setup();s.run("signedIn=true;pendingUser='tester'");for(const route of ['home','jobs','new','chat','more','more/account','more/notifications','more/approvals','more/cancellations','detail/missing']){s.run(`location.hash='#/${route}';render()`);assert.ok(s.app.innerHTML.length>200,route)}s.run("signedIn=false;render()");assert.match(s.app.innerHTML,/관리자 로그인/);assert.doesNotMatch(s.app.innerHTML,/의뢰 등록/)});

test('Android export passes CSV to the native save dialog',()=>{
  const s=setup();let saved;
  s.context.window.AllcheckApp={exportCsv:csv=>{saved=csv}};
  s.run("jobs.push({id:'TEST',customer:'=formula',phone:'010',address:'address',type:'type',status:'status',created:new Date().toISOString()})");
  s.listeners.click({target:{closest:()=>({dataset:{action:'export'}})}});
  assert.ok(saved.startsWith('\uFEFF'));
  assert.ok(saved.includes("\"'=formula\""));
  assert.ok(saved.includes('TEST'));
});

test('folder settings distinguish browser and connected Android permissions',()=>{
 const s=setup();s.run("signedIn=true;location.hash='#/more/recordings';render()");
 assert.doesNotMatch(s.app.innerHTML,/data-action="connect-recordings"/);
 let calls=0;s.context.window.AllcheckApp={recordingFolderStatus:()=> 'connected',connectRecordingFolder:()=>calls++,disconnectRecordingFolder:()=>calls--};
 s.run('render()');assert.match(s.app.innerHTML,/data-action="disconnect-recordings"/);
 s.listeners.click({target:{closest:()=>({dataset:{action:'connect-recordings'}})}});assert.equal(calls,1);
 s.listeners.click({target:{closest:()=>({dataset:{action:'disconnect-recordings'}})}});assert.equal(calls,0);
 s.context.window.AllcheckApp.recordingFolderStatus=()=> 'disconnected';s.run('render()');
 assert.match(s.app.innerHTML,/data-action="connect-recordings"/);assert.doesNotMatch(s.app.innerHTML,/data-action="disconnect-recordings"/);
});
test('every Figma screen has a reachable preview without removing old routes',()=>{const s=setup();s.run("signedIn=true;pendingUser='reviewer'");for(const f of FLOW_CATALOG){s.run("location.hash='#/scenario/"+encodeURIComponent(f.id)+"';render()");assert.ok(s.app.innerHTML.includes(f.id),f.name)}});
test('normal operations do not activate an unset failure preview flag',()=>{const s=setup();s.run("signedIn=true;pendingUser='reviewer'");assert.equal(s.run("flowUpdate(()=>Admin.seed(operations))"),true);assert.equal(s.run('operations.drivers.length'),5);s.run("flowFault='approval'");assert.equal(s.run("flowUpdate(()=>Admin.decideDriver(operations,'example-pending','활성'),'approval')"),false);assert.equal(s.run("operations.drivers.find(d=>d.id==='example-pending').status"),'승인 대기');assert.equal(s.run("flowUpdate(()=>Admin.decideDriver(operations,'example-pending','활성'),'approval')"),true)});
