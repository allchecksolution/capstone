import fs from 'node:fs';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'..');
let count=0;
function requireFile(relative){const file=path.join(root,relative);if(!fs.existsSync(file))throw Error('Missing '+relative);return fs.readFileSync(file,'utf8')}
for(const role of ['admin','driver']){
 const pubspec=requireFile(role+'/pubspec.yaml');if(!pubspec.includes('path: ../shared'))throw Error('Shared package path');
 if(!requireFile(role+'/lib/main.dart').includes('AppRole.'+role))throw Error('Role entry point');
 const native=requireFile(role+'/android/app/src/main/kotlin/kr/allcheck/flutter/'+role+'/MainActivity.kt');
 if(!native.includes('package kr.allcheck.flutter.'+role)||!native.includes('allcheck/platform'))throw Error('Platform channel mismatch');
 const gradle=requireFile(role+'/android/app/build.gradle.kts');if(!gradle.includes('kr.allcheck.flutter.'+role))throw Error('Application ID mismatch');
 for(const file of ['ios/Runner.xcodeproj/project.pbxproj','ios/Runner/Info.plist','web/index.html']){if(requireFile(role+'/'+file).includes('{{'))throw Error('Unrendered template '+file);}
}
const library=requireFile('shared/lib/allcheck_ui.dart');for(const m of library.matchAll(/part '([^']+)';/g)){requireFile('shared/lib/'+m[1]);count++;}
console.log('Structure check passed: 2 role apps, '+count+' Dart parts, native channels, iOS/web templates.');
console.log('This does not run Dart, Flutter analysis, widget tests or builds.');
