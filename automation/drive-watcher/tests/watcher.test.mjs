import test from 'node:test';
import assert from 'node:assert/strict';
import worker,{checkOnce,parseFolder,fingerprint,decodeLiteral} from '../src/index.mjs';
const FOLDER='1lC9hjcz7phWG-fQADjO54aH9Wg2RPrrB';
const row=(id='abcdefghijk',name='Приложение.apk',size=123,modified=456)=>[id,[FOLDER],name,'application/vnd.android.package-archive',0,null,0,0,0,1,modified,null,null,size];
const html=(items=[row()],token=null)=>`window['_DRIVE_ivd'] = '${JSON.stringify([items,token]).replaceAll('\\','\\\\').replaceAll("'","\\'")}';`;
class KV{
  value=null;writes=0;
  async get(){return this.value?JSON.parse(this.value):null;}
  async put(key,value){this.value=value;this.writes++;}
}
function fixture({items=[row()],published='old',runs=[],dispatchStatus=204,sourceStatus=200}={}){
  const calls=[],env={STATE:new KV(),GITHUB_TOKEN:'private-token',ADMIN_TOKEN:'admin-token'};
  async function fetcher(url,options={}){
    assert.notEqual(options.redirect,'error','Cloudflare supports manual/follow redirects, not error');
    calls.push({url,options});
    if(url.startsWith('https://drive.google.com/'))return new Response(html(items),{status:sourceStatus});
    if(url.startsWith('https://raw.githubusercontent.com/'))return new Response(JSON.stringify({format:1,folderId:FOLDER,fingerprint:published}));
    if(url.endsWith('/dispatches'))return new Response(null,{status:dispatchStatus});
    if(url.includes('/runs?'))return Response.json({workflow_runs:runs});
    throw Error('Unexpected URL');
  }
  return {env,fetcher,calls};
}
test('parser preserves Unicode, modification time and stable order',async()=>{
  assert.deepEqual(parseFolder(html([row('zzzzzzzzzzz'),row()])),[['abcdefghijk','Приложение.apk',123,456],['zzzzzzzzzzz','Приложение.apk',123,456]]);
  assert.equal(await fingerprint(parseFolder(html())),await fingerprint([['abcdefghijk','Приложение.apk',123,456]]));
  assert.equal(await fingerprint(parseFolder(html())),'34747dcc596f8ddfc1cc2c7fb5e47a468e118b2e5f0d567bff82b0e83a98c20c');
});
test('single-quoted Drive payload is decoded without evaluating code',()=>{
  assert.equal(decodeLiteral("'\\x5b\\u0430\\x5d'"),'[а]');
  assert.throws(()=>parseFolder("window['_DRIVE_ivd'] = 'globalThis.compromised=true';"));
  assert.equal(globalThis.compromised,undefined);
});
test('private, paginated, empty, duplicate and foreign listings fail closed',()=>{
  for(const source of ['Login required',html([],null),html([row()],'next'),html([row(),row()]),html([(()=>{const r=row();r[1]=['other'];return r;})()])])assert.throws(()=>parseFolder(source));
});
test('non-APK changes do not trigger a catalog update',()=>assert.deepEqual(parseFolder(html([row(),row('other123456','notes.zip')])),parseFolder(html())));
test('unchanged metadata does not call GitHub Actions',async()=>{
  const hash=await fingerprint(parseFolder(html())),x=fixture({published:hash});
  assert.equal((await checkOnce(x.env,{fetcher:x.fetcher,now:1000000})).result,'up-to-date');
  assert.equal(x.calls.length,2);
});
test('changed upload is debounced then dispatched once',async()=>{
  const x=fixture();
  assert.equal((await checkOnce(x.env,{fetcher:x.fetcher,now:1000000})).result,'waiting-for-stable-upload');
  assert.equal((await checkOnce(x.env,{fetcher:x.fetcher,now:1060000})).result,'verification-requested');
  const dispatched=x.calls.filter(c=>c.url.endsWith('/dispatches'));assert.equal(dispatched.length,1);
  assert.deepEqual(JSON.parse(dispatched[0].options.body),{ref:'main',inputs:{force:false}});
  assert.equal(dispatched[0].options.headers.Authorization,'Bearer private-token');
  assert.equal((await checkOnce(x.env,{fetcher:x.fetcher,now:1120000})).result,'retry-backoff');
});
test('newer observations reset upload stability window',async()=>{
  const x=fixture();await checkOnce(x.env,{fetcher:x.fetcher,now:1000000});
  const newer=fixture({items:[row('abcdefghijk','Приложение.apk',123,457)]});
  assert.equal((await checkOnce(x.env,{fetcher:newer.fetcher,now:1060000})).result,'waiting-for-stable-upload');
});
test('same-name same-size replacement changes the fingerprint',async()=>assert.notEqual(await fingerprint(parseFolder(html())),await fingerprint(parseFolder(html([row('abcdefghijk','Приложение.apk',123,457)])))));
test('active or queued verification prevents duplicate jobs',async()=>{
  for(const status of ['queued','in_progress','waiting']){
    const x=fixture({runs:[{status}]});
    assert.equal((await checkOnce(x.env,{fetcher:x.fetcher,now:1000000,force:true})).result,'verification-running');
    assert.equal(x.calls.filter(c=>c.url.endsWith('/dispatches')).length,0);
  }
});
test('failed dispatch is not recorded as successful and exposes no credentials',async()=>{
  const x=fixture({dispatchStatus:403});
  await assert.rejects(checkOnce(x.env,{fetcher:x.fetcher,now:1000000,force:true}),/HTTP 403/);
  assert.ok(!JSON.parse(x.env.STATE.value).lastDispatchAt);
  assert.ok(!x.env.STATE.value.includes('private-token'));
});
test('source failure never dispatches or edits the published catalog',async()=>{
  const x=fixture({sourceStatus:503});await assert.rejects(checkOnce(x.env,{fetcher:x.fetcher,now:1000000,force:true}));
  assert.equal(x.calls.length,1);
});
test('publication clears pending state after verification',async()=>{
  const x=fixture();await checkOnce(x.env,{fetcher:x.fetcher,now:1000000,force:true});
  const published=fixture({published:await fingerprint(parseFolder(html()))});
  assert.equal((await checkOnce(x.env,{fetcher:published.fetcher,now:1060000})).result,'up-to-date');
  assert.equal(JSON.parse(x.env.STATE.value).pendingHash,null);
});
test('unchanged minute polling keeps KV writes comfortably under the free daily limit',async()=>{
  const x=fixture({published:await fingerprint(parseFolder(html()))});
  for(let i=0;i<121;i++)await checkOnce(x.env,{fetcher:x.fetcher,now:1000000+i*60000,scheduled:true});
  assert.ok(x.env.STATE.writes<=14,x.env.STATE.writes);
  assert.ok(JSON.parse(x.env.STATE.value).lastScheduledAt);
});
test('manual check is private, status never exposes service secrets',async()=>{
  const x=fixture();
  assert.equal((await worker.fetch(new Request('https://example.com/check',{method:'POST'}),x.env)).status,401);
  const response=await worker.fetch(new Request('https://example.com/status'),x.env);
  const text=await response.text();assert.ok(!text.includes('private-token')&&!text.includes('admin-token'));
});
