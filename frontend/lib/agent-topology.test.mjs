import assert from 'node:assert/strict';
import { test } from 'node:test';
const { flattenTopology, stateKeyUsage } = await import('./agent-topology.ts');
const leaf = (name, inputKeys=[], outputKey=null) => ({ nodeId:'duplicate-id', name, inputKeys, outputKey, children:[] });
const root = { ...leaf('root',[], 'answer'), children:[leaf('writer',['request'], 'draft'),leaf('reviewer',['draft'],'answer')] };
test('outline uses unique structural paths even when node IDs repeat',()=>{
 const entries=flattenTopology(root);
 assert.deepEqual(entries.map(e=>[e.path,e.depth,e.parentPath]),[['0',0,null],['0.0',1,'0'],['0.1',1,'0']]);
});
test('state usage includes all consumers and producers without treating external input as a missing node',()=>{
 const usage=stateKeyUsage(flattenTopology(root),'draft');
 assert.deepEqual(usage.producers.map(e=>e.node.name),['writer']);
 assert.deepEqual(usage.consumers.map(e=>e.node.name),['reviewer']);
 assert.deepEqual(stateKeyUsage(flattenTopology(root),'request').producers,[]);
});
