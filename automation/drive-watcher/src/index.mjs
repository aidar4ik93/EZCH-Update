const DEFAULT_FOLDER='1lC9hjcz7phWG-fQADjO54aH9Wg2RPrrB';
const REPO='aidar4ik93/EZCH-Update';
const WORKFLOW='sync-google-drive-catalog.yml';
const KEY='drive-catalog-v1';
const LIMIT=4*1024*1024;

// Decode the quoted public Drive payload as data. Never eval HTML/JavaScript from Drive.
export function decodeLiteral(quoted){
  if(quoted[0]!=="'"||quoted.at(-1)!=="'")throw Error('Invalid Drive literal');
  let result='';
  for(let i=1;i<quoted.length-1;i++){
    let ch=quoted[i];
    if(ch==='\\'){
      ch=quoted[++i];
      if(ch==='x'||ch==='u'){
        const size=ch==='x'?2:4,hex=quoted.slice(i+1,i+1+size);
        if(!new RegExp(`^[0-9a-fA-F]{${size}}$`).test(hex))throw Error('Invalid Drive escape');
        result+=String.fromCharCode(parseInt(hex,16));i+=size;continue;
      }
      const escapes={n:'\n',r:'\r',t:'\t',b:'\b',f:'\f',v:'\v',"'":"'",'"':'"','\\':'\\','/':'/'};
      if(!(ch in escapes))throw Error('Unsupported Drive escape');
      ch=escapes[ch];
    }
    result+=ch;
  }
  return result;
}

export function parseFolder(html,folder=DEFAULT_FOLDER){
  const match=/window\['_DRIVE_ivd'\]\s*=\s*('(?:[^'\\]|\\.)*');/.exec(html);
  if(!match)throw Error('Drive folder is private or its public format changed');
  const data=JSON.parse(decodeLiteral(match[1]));
  if(!Array.isArray(data)||!Array.isArray(data[0])||data.length<2||data[1]!==null||data[0].length>=50)throw Error('Incomplete or paginated Drive listing');
  const seen=new Set(),files=[];
  for(const item of data[0]){
    if(!Array.isArray(item)||item.length<14)throw Error('Incomplete Drive metadata');
    const [id,parents,name,mime]=item;
    if(typeof id!=='string'||!/^[A-Za-z0-9_-]{10,}$/.test(id)||seen.has(id)||!Array.isArray(parents)||parents.length!==1||parents[0]!==folder)throw Error('Unexpected Drive file or duplicate');
    seen.add(id);
    if(mime==='application/vnd.google-apps.folder')throw Error('Nested folders require a full recursive scan');
    if(typeof name!=='string'||!name)throw Error('Invalid Drive name');
    if(!name.toLowerCase().endsWith('.apk'))continue;
    const size=item[13],modified=item[10];
    if(!Number.isSafeInteger(size)||size<=0||size>2*1024*1024*1024||!Number.isSafeInteger(modified))throw Error('Invalid Drive file size/time');
    files.push([id,name,size,modified]);
  }
  if(!files.length)throw Error('No public APKs; preserve the current catalog');
  return files.sort((a,b)=>a[0]<b[0]?-1:a[0]>b[0]?1:0);
}

export async function fingerprint(files){
  const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(files)));
  return [...new Uint8Array(digest)].map(x=>x.toString(16).padStart(2,'0')).join('');
}

async function readBounded(response){
  const reader=response.body.getReader(),chunks=[];let total=0;
  try{while(true){const {value,done}=await reader.read();if(done)break;total+=value.length;if(total>LIMIT)throw Error('Source listing exceeds limit');chunks.push(value);}}
  finally{await reader.cancel().catch(()=>{});}
  const bytes=new Uint8Array(total);let offset=0;for(const c of chunks){bytes.set(c,offset);offset+=c.length;}
  return new TextDecoder('utf-8',{fatal:true}).decode(bytes);
}

async function request(fetcher,url,options={}){
  const r=await fetcher(url,{...options,signal:AbortSignal.timeout(15000),redirect:'error'});
  if(!r.ok)throw Error(`Upstream HTTP ${r.status}`);
  return r;
}

export async function checkOnce(env,{fetcher=fetch,now=Date.now(),force=false,scheduled=false}={}){
  if(!env.STATE||!env.GITHUB_TOKEN)throw Error('Watcher is not configured');
  const folder=env.DRIVE_FOLDER_ID||DEFAULT_FOLDER;
  if(folder!==DEFAULT_FOLDER)throw Error('Unexpected source folder');
  const old=(await env.STATE.get(KEY,{type:'json'}))||{},state={...old};
  let forcePersist=false;
  async function save(result){
    state.lastResult=result;
    if(scheduled&&!state.lastScheduledAt){state.lastScheduledAt=now;forcePersist=true;}
    if(forcePersist||now-(old.lastPersistedAt||0)>=600000){
      state.lastCheckAt=now;state.lastPersistedAt=now;
      if(scheduled)state.lastScheduledAt=now;
      await env.STATE.put(KEY,JSON.stringify(state));
    }
    return {result,fingerprint:state.observedHash,apkCount:state.apkCount};
  }
  try{
    const source=await request(fetcher,`https://drive.google.com/drive/folders/${folder}`,{headers:{'User-Agent':'EZCH-Drive-Watcher/1','Cache-Control':'no-cache'}});
    const files=parseFolder(await readBounded(source),folder),hash=await fingerprint(files);
    state.apkCount=files.length;
    let published=null;
    const response=await fetcher(`https://raw.githubusercontent.com/${REPO}/main/catalog-source.json?check=${Math.floor(now/60000)}`,{signal:AbortSignal.timeout(15000),redirect:'error'});
    if(response.ok){const data=JSON.parse(await readBounded(response));if(data.format===1&&data.folderId===folder)published=data.fingerprint;}
    else if(response.status!==404)throw Error(`Published snapshot HTTP ${response.status}`);
    if(hash!==state.observedHash){state.observedHash=hash;state.observedAt=now;state.attempts=0;state.lastDispatchAt=0;forcePersist=true;}
    if(hash===published&&!force){
      if(state.pendingHash||state.lastError){state.pendingHash=null;state.lastError=null;forcePersist=true;}
      state.publishedHash=hash;return save('up-to-date');
    }
    if(!force&&now-state.observedAt<40000)return save('waiting-for-stable-upload');
    const headers={Authorization:`Bearer ${env.GITHUB_TOKEN}`,Accept:'application/vnd.github+json','User-Agent':'EZCH-Drive-Watcher/1','Content-Type':'application/json'};
    const list=await request(fetcher,`https://api.github.com/repos/${REPO}/actions/workflows/${WORKFLOW}/runs?branch=main&per_page=10`,{headers});
    const runs=(await list.json()).workflow_runs;
    if(!Array.isArray(runs))throw Error('Invalid workflow status response');
    if(runs.some(r=>['queued','in_progress','waiting','requested','pending'].includes(r.status)))return save('verification-running');
    const backoff=Math.min(3600000,300000*2**Math.max(0,(state.attempts||0)-1));
    if(!force&&state.lastDispatchAt&&now-state.lastDispatchAt<backoff)return save('retry-backoff');
    const dispatched=await request(fetcher,`https://api.github.com/repos/${REPO}/actions/workflows/${WORKFLOW}/dispatches`,{method:'POST',headers,body:JSON.stringify({ref:'main',inputs:{force}})});
    if(dispatched.status!==204)throw Error('Workflow dispatch was not accepted');
    state.pendingHash=hash;state.lastDispatchAt=now;state.attempts=(state.attempts||0)+1;state.lastError=null;forcePersist=true;
    return save('verification-requested');
  }catch(error){
    // Persist a bounded diagnostic, never a token, response body or private request headers.
    const message=error instanceof Error?error.message:'Watcher check failed';
    if(state.lastError!==message){state.lastError=message;forcePersist=true;}
    await save('error');throw error;
  }
}

export default{
  async scheduled(event,env,ctx){ctx.waitUntil(checkOnce(env,{scheduled:true}));},
  async fetch(request,env){
    const url=new URL(request.url);
    if(request.method==='GET'&&url.pathname==='/status'){
      const state=(await env.STATE.get(KEY,{type:'json'}))||{};
      return Response.json({service:'EZCH Drive catalog watcher',checkIntervalSeconds:60,folderId:DEFAULT_FOLDER,
        lastCheckAt:state.lastCheckAt||null,lastScheduledAt:state.lastScheduledAt||null,result:state.lastResult||'not-checked',
        apkCount:state.apkCount||null,lastDispatchAt:state.lastDispatchAt||null,lastError:state.lastError||null},
        {headers:{'Cache-Control':'no-store'}});
    }
    if(request.method==='POST'&&url.pathname==='/check'){
      if(!env.ADMIN_TOKEN||request.headers.get('Authorization')!==`Bearer ${env.ADMIN_TOKEN}`)return new Response('Unauthorized',{status:401});
      try{return Response.json(await checkOnce(env,{force:url.searchParams.get('force')==='true'}));}
      catch{return Response.json({error:'Source check failed; existing catalog preserved'},{status:502});}
    }
    return new Response('Not found',{status:404});
  }
};
