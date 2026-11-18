import assert from 'node:assert/strict';
import {keepAliveChecks,keepAliveSummary,debugStatus,debugText} from './keepalive.ts';

const healthy:any={
  flavor:'mobile',
  monitor:{service:true,overlay:true,usage:true,accessibility:true,accessibilityEnabled:true,notificationListener:true,notificationListenerConnected:true,lastTickSeconds:1},
  keepalive:{ignoringBattery:true,notifications:true,sdk:34,brandTip:'小米手机：设置 → 应用设置 → 应用管理'},
  autostart:{known:true,ok:true,pending:false,via:'boot',startedAfterSeconds:12,bootBroadcast:true}
};

const ok=keepAliveChecks(healthy);
assert.equal(ok.length,8);
assert.equal(ok.every(item=>item.level==='ok'),true);
assert.deepEqual(keepAliveSummary(healthy),{level:'ok',text:'全部正常',problems:0,pending:0});

const broken:any={
  ...healthy,
  monitor:{service:false,overlay:false,usage:false,accessibility:false,accessibilityEnabled:false,notificationListener:false,notificationListenerConnected:false,lastTickSeconds:-1},
  keepalive:{...healthy.keepalive,ignoringBattery:false,notifications:false},
  autostart:{...healthy.autostart,ok:false}
};
assert.ok(keepAliveChecks(broken).filter(item=>item.level==='warn').length>=5);
assert.equal(keepAliveSummary(broken).level,'warn');
assert.match(keepAliveSummary(broken).text,/项需要处理/);

const pending:any={...healthy,autostart:{known:true,ok:false,pending:true,via:'',startedAfterSeconds:-1}};
assert.equal(keepAliveSummary(pending).level,'unknown');
assert.match(keepAliveSummary(pending).text,/待确认/);

const noBootCount:any={...healthy,autostart:{known:false,ok:false,pending:false}};
assert.equal(keepAliveChecks(noBootCount).find(item=>item.id==='autostart')?.level,'unknown');

const tv:any={
  flavor:'tv',
  monitor:{service:true,overlay:true,usage:false,accessibility:true,accessibilityEnabled:true,notificationListener:true,notificationListenerConnected:true,lastTickSeconds:2},
  keepalive:{ignoringBattery:false,notifications:false,sdk:34},
  autostart:{known:true,ok:true,pending:false,via:'boot',startedAfterSeconds:30}
};
const tvChecks=keepAliveChecks(tv);
assert.equal(tvChecks.some(item=>item.id==='notifications'),false);
assert.equal(tvChecks.find(item=>item.id==='battery')?.level,'unknown');
assert.equal(tvChecks.find(item=>item.id==='usage')?.level,'ok');
assert.equal(tvChecks.find(item=>item.id==='listener')?.level,'ok');
assert.equal(keepAliveChecks(broken).find(item=>item.id==='listener')?.level,'unknown');

// #141：小米等电视没有无障碍设置入口，不能再报「需要处理」
const tvNoA11y:any={...tv,monitor:{...tv.monitor,usage:true,accessibility:false,accessibilityEnabled:false}};
const tvNoA11yCheck=keepAliveChecks(tvNoA11y).find(item=>item.id==='accessibility');
assert.equal(tvNoA11yCheck?.level,'unknown');
assert.match(tvNoA11yCheck?.detail||'',/可以忽略/);
assert.equal(keepAliveSummary(tvNoA11y).level,'unknown');
assert.equal(keepAliveChecks({...broken,flavor:'tv'}).find(item=>item.id==='accessibility')?.level,'unknown');

const stale:any={...healthy,monitor:{...healthy.monitor,lastTickSeconds:42}};
assert.match(keepAliveChecks(stale).find(item=>item.id==='service')?.detail||'',/42/);
assert.equal(keepAliveSummary(stale).level,'warn');

assert.deepEqual(keepAliveChecks(null),[]);
assert.equal(keepAliveSummary(null).level,'unknown');

const debugOn:any={keepalive:{usbDebugging:true,developerOptions:true}};
const debugOff:any={keepalive:{usbDebugging:false,developerOptions:false}};
assert.deepEqual(debugStatus(debugOn),{usb:true,developer:true});
assert.deepEqual(debugStatus(debugOff),{usb:false,developer:false});
assert.deepEqual(debugStatus(null),{usb:false,developer:false});
assert.equal(debugText(debugOn),'- 开发者选项：已开启\n- USB 调试：已开启');
assert.equal(debugText(debugOff),'- 开发者选项：未开启\n- USB 调试：未开启');
console.log('PASS keepalive checks: healthy, broken, pending reboot, no boot count, TV substitutions (无障碍入口缺失可忽略), stale heartbeat, empty data and debug status');