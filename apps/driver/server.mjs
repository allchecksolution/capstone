import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=fileURLToPath(new URL('.',import.meta.url));
http.createServer(async(req,res)=>{try{const pathname=decodeURIComponent(new URL(req.url,'http://localhost').pathname);const file=path.resolve(root,'.'+(pathname==='/'?'/index.html':pathname));const rel=path.relative(root,file);if(rel.startsWith('..')||path.isAbsolute(rel))throw Error();if(!['index.html','style.css','app.js','model.js','flow-catalog.js'].includes(rel)&&!rel.startsWith('assets'+path.sep))throw Error();const body=await readFile(file);res.writeHead(200,{'Content-Type':({'html':'text/html; charset=utf-8','css':'text/css','js':'text/javascript','svg':'image/svg+xml'})[path.extname(file).slice(1)]||'application/octet-stream','Cache-Control':'no-store'});res.end(body)}catch{res.writeHead(404);res.end('Not found')}}).listen(5174,'0.0.0.0',()=>console.log('ALLCHECK driver: http://localhost:5174'));
