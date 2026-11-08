// Verify the optional web renderer agrees with the vehicle catalogue at every landmark.
import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const root=new URL('../',import.meta.url);
const catalog=JSON.parse(await fs.readFile(new URL('app/stamps/catalog.json',root),'utf8'));
const source=await fs.readFile(new URL('server/src/main/resources/static/m/card.js',root),'utf8');
assert.ok(source.includes('window.DriveCard = {'));
const context={window:{}};
// Expose the private matcher only inside this isolated verification context.
vm.runInNewContext(source.replace('window.DriveCard = {','window.DriveCard = { stampFor,'),context);
const match=context.window.DriveCard.stampFor;
const record=(p)=>({route:[[35,128],[p.latitude,p.longitude]],distanceKm:5,simulated:false});
let count=0;
for(const p of catalog.regions.flatMap(r=>r.places)){
 const r=record(p);
 assert.equal(match(r)?.name,p.name,p.id);
 assert.equal(match(r)?.tilt,p.tilt,p.id);
 assert.equal(match({...r,simulated:true}),null,p.id+' simulation');
 assert.equal(match({...r,distanceKm:0.05}),null,p.id+' short drive');
 assert.equal(match({...r,route:[r.route[1]]}),null,p.id+' missing route');
 count++;
}
assert.equal(match({route:[[0,0],[0,0]],distanceKm:5}),null);
console.log(`Card matching: ${count} landmarks, nearest point, simulation, short and missing-route checks passed.`);
