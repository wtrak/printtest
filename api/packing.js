'use strict';
/**
 * JTK Packing Station: protected Google Sheets API.
 * All records stay in the owner's Google Sheet. Never embed the service-account
 * private key, a refresh token, or the station password in Android or browser code.
 *
 * Required Vercel environment variables:
 * GOOGLE_SERVICE_ACCOUNT_EMAIL
 * GOOGLE_PRIVATE_KEY
 * PACKING_APP_PASSWORD
 * PACKING_SESSION_SECRET
 *
 * JTK_SHEET_ID is optional, defaulting to the already-created packing workbook.
 */
const crypto = require('crypto');
const SHEET_ID = process.env.JTK_SHEET_ID || '1h9SpQUDBdbpqQV7bgQeFCqSgdTkSPPz_meiD6HV6MhI';
const SESSION_AGE=60*60*24*7;
const headers=['Bidcard','Name','Email','Street','City','State','Zip','Type','Small Item','Medium Item','Large Item','Pounds','Ounces','Length','Width','Height','International 1','International 2','International 3'];
const pheaders=['Package ID','Auction ID','Customer Key','Bidcard','Customer Name','Email','Box Type','Small Item','Medium Item','Large Item','Pounds','Ounces','Length','Width','Height','Packed At','Print Status','Packing Station'];
let token=null,tokenExpiry=0;

function b64(input){return Buffer.from(input).toString('base64url')}
function configured(){return !!(process.env.GOOGLE_SERVICE_ACCOUNT_EMAIL&&process.env.GOOGLE_PRIVATE_KEY&&process.env.PACKING_APP_PASSWORD&&process.env.PACKING_SESSION_SECRET&&process.env.PACKING_SESSION_SECRET.length>=24)}
function reply(res,status,body){res.statusCode=status;res.setHeader('Content-Type','application/json; charset=utf-8');res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');res.end(JSON.stringify(body))}
function err(code,message){const e=new Error(message);e.status=code;return e}
function sign(data){return crypto.createHmac('sha256',process.env.PACKING_SESSION_SECRET).update(data).digest('base64url')}
function makeSession(){const data=b64(JSON.stringify({v:1,exp:Date.now()+SESSION_AGE*1000}));return data+'.'+sign(data)}
function auth(req){
 if(!configured())return false;
 const cookies=String(req.headers.cookie||'').split(';').map(x=>x.trim());
 const c=cookies.find(x=>x.startsWith('jtk_session='));
 if(!c)return false;const value=c.slice('jtk_session='.length),parts=value.split('.');
 if(parts.length!==2||!parts[1])return false;
 const right=Buffer.from(sign(parts[0]));
 const received=Buffer.from(parts[1]);
 if(right.length!==received.length||!crypto.timingSafeEqual(right,received))return false;
 try {const d=JSON.parse(Buffer.from(parts[0],'base64url').toString('utf8'));return d.v===1&&d.exp>Date.now()}catch{return false}
}
function sameOrigin(req){
 const o=req.headers.origin;
 if(!o)return false; // All app writes are same-origin browser requests
 try{
 const url=new URL(o);
 const host=String(req.headers['x-forwarded-host']||req.headers.host||'');
 return url.protocol==='https:'&&url.host===host;
 }catch{return false}
}
async function body(req){
 let s='';for await(const chunk of req){s+=chunk.toString('utf8');if(s.length>450000)throw err(413,'Request too large')}
 try{return s?JSON.parse(s):{}}catch{throw err(400,'Invalid JSON')}
}
function checkedText(value,max,label,mandatory=false){
 if(value===undefined||value===null)value='';
 const s=String(value).trim();if(s.length>max||/[\u0000-\u001f]/.test(s))throw err(400,'Invalid '+label);
 if(mandatory&&!s)throw err(400,'Missing '+label);return s;
}
function number(value,max,label,{integer=false,positive=false}={}){
 const n=Number(value);
 if(!Number.isFinite(n)||n<0||n>max||(integer&&!Number.isInteger(n))||(positive&&n<=0))throw err(400,'Invalid '+label);
 return n;
}
function encodeRange(range){return encodeURIComponent(range)}
async function accessToken(){
 if(token&&Date.now()<tokenExpiry-60000)return token;
 const email=process.env.GOOGLE_SERVICE_ACCOUNT_EMAIL;
 const key=process.env.GOOGLE_PRIVATE_KEY.replace(/\\n/g,'\n');
 const now=Math.floor(Date.now()/1000);
 const head=b64(JSON.stringify({alg:'RS256',typ:'JWT'}));
 const claims=b64(JSON.stringify({iss:email,scope:'https://www.googleapis.com/auth/spreadsheets',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600}));
 const unsigned=head+'.'+claims;
 const signature=crypto.sign('RSA-SHA256',Buffer.from(unsigned),key).toString('base64url');
 const resp=await fetch('https://oauth2.googleapis.com/token',{
   method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
   body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:unsigned+'.'+signature})
 });
 const j=await resp.json();if(!resp.ok)throw err(503,'Google authentication failed. Check the service account configuration.');
 token=j.access_token;tokenExpiry=Date.now()+(j.expires_in||3600)*1000;return token;
}
async function sheets(method,apiPath,payload){
 const t=await accessToken();
 const url='https://sheets.googleapis.com/v4/spreadsheets/'+encodeURIComponent(SHEET_ID)+apiPath;
 const r=await fetch(url,{method,headers:{Authorization:'Bearer '+t,'Content-Type':'application/json'},body:payload?JSON.stringify(payload):undefined});
 const j=await r.json();
 if(!r.ok){
   console.error('Google Sheets request failed:',r.status,j?.error?.status||'');
   throw err(503,'Google Sheets request failed. Check spreadsheet sharing, range, and Google API access.');
 }
 return j;
}
async function readRows(name,lastColumn='W',lastRow=3000){
 const j=await sheets('GET','/values/'+encodeRange(name+'!A1:'+lastColumn+lastRow)+'?valueRenderOption=FORMATTED_VALUE');
 return j.values||[];
}
async function setRange(a1,values){
 return sheets('PUT','/values/'+encodeRange(a1)+'?valueInputOption=RAW',{range:a1,majorDimension:'ROWS',values});
}
async function appendRange(a1,values){
 return sheets('POST','/values/'+encodeRange(a1)+':append?valueInputOption=RAW&insertDataOption=INSERT_ROWS',{range:a1,majorDimension:'ROWS',values});
}
function item(r,i){return String(r?.[i]??'')}
function asCustomer(r,index){return {
  row:index+1,auctionId:item(r,0),customerKey:item(r,1),bidcard:item(r,2),
  name:item(r,3),email:item(r,4),packingStatus:item(r,21)||'Not packed'
}}
function asPackage(r,index){return {
  row:index+1,id:item(r,0),auctionId:item(r,1),customerKey:item(r,2),bidcard:item(r,3),
  name:item(r,4),email:item(r,5),boxType:item(r,6),small:Number(r?.[7]||0),medium:Number(r?.[8]||0),
  large:Number(r?.[9]||0),pounds:Number(r?.[10]||0),ounces:Number(r?.[11]||0),
  length:Number(r?.[12]||0),width:Number(r?.[13]||0),height:Number(r?.[14]||0),
  packedAt:item(r,15),printStatus:item(r,16),station:item(r,17)
}}
function validatePackage(data){
 const auctionId=checkedText(data.auctionId,50,'auction',true);
 const customerKey=checkedText(data.customerKey,100,'customer',true);
 if(!customerKey.startsWith(auctionId+':'))throw err(400,'Customer does not belong to selected auction');
 const id=checkedText(data.id,80,'package ID',true);
 if(!/^JTK-[0-9A-F-]{10,75}$/.test(id))throw err(400,'Invalid package ID');
 const p={
  id,auctionId,customerKey,
  boxType:checkedText(data.boxType||'Box',40,'box type'),
  small:number(data.small,999,'small items',{integer:true}),
  medium:number(data.medium,999,'medium items',{integer:true}),
  large:number(data.large,999,'large items',{integer:true}),
  pounds:number(data.pounds,150,'pounds',{integer:true}),
  ounces:number(data.ounces,15.99,'ounces'),
  length:number(data.length,200,'length',{positive:true}),
  width:number(data.width,200,'width',{positive:true}),
  height:number(data.height,200,'height',{positive:true}),
  station:checkedText(data.station||'Android station',50,'station')
 };
 if(p.pounds===0&&p.ounces===0)throw err(400,'Weight must be greater than zero');
 return p;
}
module.exports=async function handler(req,res){
 try{
  const action=new URL(req.url,'https://example.local').searchParams.get('action')||'health';
  if(action==='health')return reply(res,200,{ok:true,configured:configured(),storage:'Google Sheets'});
  if(!configured())return reply(res,503,{error:'Packing server needs Google Sheet credentials and a station password. No tablet data is being saved.'});
  if(req.method==='POST'&&!sameOrigin(req))throw err(403,'Request origin not allowed');
  if(action==='login'){
   if(req.method!=='POST')throw err(405,'Method not allowed');
   const d=await body(req),pw=checkedText(d.password,200,'password');
   const e=crypto.createHash('sha256').update(process.env.PACKING_APP_PASSWORD).digest();
   const a=crypto.createHash('sha256').update(pw).digest();
   if(!crypto.timingSafeEqual(a,e))throw err(401,'Incorrect station password');
   res.setHeader('Set-Cookie','jtk_session='+makeSession()+'; Path=/; Max-Age='+SESSION_AGE+'; HttpOnly; Secure; SameSite=Strict');
   return reply(res,200,{ok:true});
  }
  if(action==='logout'){
   res.setHeader('Set-Cookie','jtk_session=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict');
   return reply(res,200,{ok:true});
  }
  if(!auth(req))throw err(401,'Station login required');
  if(action==='session')return reply(res,200,{ok:true});
  if(action==='auctions'&&req.method==='GET'){
   const rows=(await readRows('Auctions','F',1000)).slice(1);
   const data=rows.filter(r=>item(r,0)).map(r=>({id:item(r,0),name:item(r,1),date:item(r,2),count:Number(r?.[4]||0),status:item(r,5)})).sort((a,b)=>b.date.localeCompare(a.date)||a.name.localeCompare(b.name));
   return reply(res,200,{auctions:data});
  }
  if(action==='customers'&&req.method==='GET'){
   const id=new URL(req.url,'https://example.local').searchParams.get('auctionId');
   if(!id)throw err(400,'Auction required');
   const rows=(await readRows('Customers')).slice(1);
   return reply(res,200,{customers:rows.map((r,i)=>asCustomer(r,i+1)).filter(x=>x.auctionId===id).map(({row,...c})=>c).sort((a,b)=>a.name.localeCompare(b.name))});
  }
  if(action==='packages'&&req.method==='GET'){
   const id=new URL(req.url,'https://example.local').searchParams.get('auctionId');
   if(!id)throw err(400,'Auction required');
   const rows=(await readRows('Packages','R',3000)).slice(1);
   return reply(res,200,{packages:rows.map((r,i)=>asPackage(r,i+1)).filter(x=>x.id&&x.auctionId===id).map(({row,...p})=>p)});
  }
  if(action==='exportCsv'&&req.method==='GET'){
   const auctionId=new URL(req.url,'https://example.local').searchParams.get('auctionId');
   if(!auctionId)throw err(400,'Select an auction before export');
   const [customerRows,packageRows]=await Promise.all([readRows('Customers'),readRows('Packages','R',3000)]);
   const byKey=new Map(customerRows.slice(1).map(r=>[item(r,1),r]));
   const safeCsv=(v)=>{
    let s=String(v==null?'':v);
    // Protect spreadsheet opening from CSV formula injection.
    if(/^\s*[=+@]/.test(s))s="'"+s;
    if(/[",\r\n]/.test(s))s='"'+s.replace(/"/g,'""')+'"';
    return s;
   };
   const data=packageRows.slice(1).filter(r=>item(r,0)&&item(r,1)===auctionId).map(r=>{
    const c=byKey.get(item(r,2))||[];
    return [
     item(r,3),item(r,4),item(r,5),item(c,5),item(c,6),item(c,7),item(c,8),
     item(r,6),item(r,7),item(r,8),item(r,9),item(r,10),item(r,11),
     item(r,12),item(r,13),item(r,14),item(c,18),item(c,19),item(c,20)
    ];
   });
   const csv=[headers.join(','),...data.map(r=>r.map(safeCsv).join(','))].join('\r\n')+'\r\n';
   res.statusCode=200;
   res.setHeader('Content-Type','text/csv; charset=utf-8');
   res.setHeader('Content-Disposition','attachment; filename="jtk-packing-ready-'+auctionId.replace(/[^A-Za-z0-9_-]/g,'')+'.csv"');
   res.setHeader('Cache-Control','no-store');
   return res.end(csv);
  }
  if(action==='savePackage'&&req.method==='POST'){
   const p=validatePackage(await body(req));
   const [customerRows,packageRows]=await Promise.all([readRows('Customers'),readRows('Packages','R',3000)]);
   const ci=customerRows.findIndex((r,i)=>i>0&&item(r,1)===p.customerKey);
   if(ci<1)throw err(404,'Customer not found in Google Sheet');
   const cr=customerRows[ci];
   if(item(cr,0)!==p.auctionId)throw err(400,'Customer-auction mismatch');
   const prior=packageRows.findIndex((r,i)=>i>0&&item(r,0)===p.id);
   if(prior>=1)return reply(res,200,{package:asPackage(packageRows[prior],prior+1),duplicate:true});
   const t=new Date().toISOString();
   const row=[p.id,p.auctionId,p.customerKey,item(cr,2),item(cr,3),item(cr,4),
    p.boxType,p.small,p.medium,p.large,p.pounds,p.ounces,p.length,p.width,p.height,t,'Saved',p.station];
   await appendRange('Packages!A:R',[row]);
   let warning=null;
   try{await setRange('Customers!V'+(ci+1)+':W'+(ci+1),[['Packed',t]])}
   catch(e){warning='Package saved, but customer packing status could not be updated.';console.error('Post-save customer status failed')}
   return reply(res,201,{package:asPackage(row,0),warning});
  }
  if(action==='printStatus'&&req.method==='POST'){
   const d=await body(req),id=checkedText(d.id,80,'package ID',true);
   const status=checkedText(d.printStatus,120,'print status',true);
   if(!['Data sent — verify label','Print failed','Reprint sent — verify label'].includes(status))throw err(400,'Invalid print status');
   const rows=await readRows('Packages','R',3000);
   const i=rows.findIndex((r,j)=>j>0&&item(r,0)===id);
   if(i<1)throw err(404,'Package not found');
   await setRange('Packages!Q'+(i+1),[[status]]);
   return reply(res,200,{ok:true});
  }
  if(action==='importAuction'&&req.method==='POST'){
   const d=await body(req);
   const letter=checkedText(d.letter,1,'auction letter',true).toUpperCase();
   if(!/^[A-F]$/.test(letter))throw err(400,'Auction must be A, B, C, D, E or F');
   const date=checkedText(d.date,10,'auction date',true);
   if(!/^\d{4}-\d{2}-\d{2}$/.test(date)||!Number.isFinite(Date.parse(date))||new Date(date).toISOString().slice(0,10)!==date)throw err(400,'Invalid auction date');
   const auctionId='Auction-'+letter+'_'+date;
   if(!Array.isArray(d.rows)||d.rows.length===0||d.rows.length>500)throw err(400,'Upload 1–500 customer rows');
   const auctionRows=await readRows('Auctions','F',1000);
   if(auctionRows.some((r,i)=>i>0&&item(r,0)===auctionId))throw err(409,'That auction already exists. Existing auctions cannot be replaced through this importer.');
   const bids=new Set();
   const data=d.rows.map((r)=>{
     const o={};for(const h of headers)o[h]=checkedText(r[h],250,h);
     if(!o.Bidcard||!o.Name)throw err(400,'Every row needs a bidcard and name');
     if(bids.has(o.Bidcard))throw err(400,'Duplicate bidcard '+o.Bidcard+' in CSV');
     bids.add(o.Bidcard);
     return [auctionId,auctionId+':'+o.Bidcard,...headers.map(h=>o[h]),'Not packed',''];
   });
   const source=checkedText(d.source||'Uploaded on packing dashboard',150,'source');
   // Add customers first; never make an auction selectable with no customer data.
   await appendRange('Customers!A:W',data);
   await appendRange('Auctions!A:F',[[auctionId,'Auction '+letter,date,source,data.length,'Active']]);
   return reply(res,201,{ok:true,auctionId,count:data.length});
  }
  throw err(404,'Unknown API action');
 }catch(e){console.error('Packing API:',e.status||500,String(e.message||e));reply(res,e.status||500,{error:e.status?e.message:'Packing service error. No changes to Android-local data.'})}
};
