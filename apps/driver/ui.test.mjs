import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import * as Model from './model.js';
import {FLOW_CATALOG} from './flow-catalog.js';
const source=(await readFile(new URL('./app.js',import.meta.url),'utf8')).split('\n').filter(l=>!l.startsWith('import ')).join('\n');
function setup(){const store=new Map(),app={innerHTML:'',addEventListener(){}},toast={style:{}};const c=vm.createContext({...Model,FLOW_CATALOG,document:{querySelector:s=>s==='#app'?app:s==='#toast'?toast:null},localStorage:{getItem:k=>store.get(k),setItem:(k,v)=>store.set(k,v)},location:{hash:''},window:{addEventListener(){},scrollTo(){}},navigator:{onLine:true},setTimeout:()=>0,clearTimeout(){},setInterval:()=>0,structuredClone,crypto,Date,console});vm.runInContext(source,c);return {app,run:s=>vm.runInContext(s,c)}}
test('legacy Apple login, detailed statistics and logout confirmation remain alongside ver2',()=>{const s=setup();assert.match(s.app.innerHTML,/Apple로 시작하기/);s.run("state.account={status:'활성',profile:{name:'기사'}};signedIn=true;location.hash='#/stats';render()");assert.match(s.app.innerHTML,/전문 분야별 완료 건수/);assert.match(s.app.innerHTML,/조회 월/)});
test('all driver Figma frames have reachable common state previews',()=>{const s=setup();s.run("state.account={status:'활성',profile:{name:'기사'}};signedIn=true");for(const f of FLOW_CATALOG){s.run("location.hash='#/scenario/"+encodeURIComponent(f.id)+"';render()");assert.ok(s.app.innerHTML.includes(f.id),f.name)}});
test('inactive account still blocks newly added statistics and scenarios',()=>{const s=setup();s.run("state.account={status:'승인 대기',profile:{}};signedIn=true;location.hash='#/stats';render()");assert.match(s.app.innerHTML,/승인 대기/);assert.doesNotMatch(s.app.innerHTML,/전문 분야별 완료 건수/)});
