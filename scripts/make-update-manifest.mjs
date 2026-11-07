// 生成 update.json：GitHub API 不可用时，客户端会通过 raw.githubusercontent / jsDelivr / 加速镜像读取它。
// 用法：node scripts/make-update-manifest.mjs [APK 所在目录，默认 release-artifacts] [--notes "更新说明"] [--force]
// 生成后把 update.json 和 Release 一起提交到 main 分支（jsDelivr 缓存最长约 12 小时）。
import {createHash} from 'node:crypto';
import {readFileSync,readdirSync,writeFileSync,statSync} from 'node:fs';
import {join,resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const args=process.argv.slice(2);
const flag=name=>{const i=args.indexOf(name);if(i<0)return undefined;const v=args[i+1];args.splice(i,2);return v;};
const force=args.includes('--force');if(force)args.splice(args.indexOf('--force'),1);
const notes=flag('--notes')??'';
const dir=resolve(root,args[0]||'release-artifacts');
const gradle=readFileSync(join(root,'app/native/app/build.gradle.kts'),'utf8');
const version=gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1];
const versionCode=Number(gradle.match(/versionCode\s*=\s*(\d+)/)?.[1]);
if(!version)throw new Error('build.gradle.kts 里找不到 versionName');
const files=readdirSync(dir).filter(f=>f.endsWith(`-v${version}.apk`));
const pick=marker=>files.find(f=>f.includes(marker));
const asset=name=>{if(!name)return undefined;const path=join(dir,name);return{name,url:`https://github.com/chadyi/StudyOnTV/releases/download/v${version}/${name}`,sha256:createHash('sha256').update(readFileSync(path)).digest('hex'),size:statSync(path).size};};
const assets={tv:asset(pick('-TV-')),mobile:asset(pick('-Mobile-'))};
if(!assets.tv&&!assets.mobile)throw new Error(`${dir} 里没有 *-TV-v${version}.apk / *-Mobile-v${version}.apk`);
const manifest={version,versionCode,force:force||notes.includes('[force]'),notes,publishedAt:new Date().toISOString(),assets};
writeFileSync(join(root,'update.json'),JSON.stringify(manifest,null,2)+'\n');
console.log(`update.json → v${version}`,Object.entries(assets).filter(([,a])=>a).map(([k,a])=>`${k}: ${a.name}`).join(', '));
