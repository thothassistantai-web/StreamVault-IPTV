// inject_translations.js
// Node.js script to inject missing translations into Android strings.xml files

const fs = require('fs');
const path = require('path');

const resDir = String.raw`c:\Users\david\.gemini\antigravity\scratch\iptv-player\app\src\main\res`;
const chatResDir = String.raw`c:\Users\david\AppData\Roaming\Code\User\workspaceStorage\cc954ace92abe3dd58fef056c4c840c5\GitHub.copilot-chat\chat-session-resources\4689e49b-8fc4-49dd-b09d-540ac637d9a5`;

const translationFiles = [
    { file: 'toolu_01ReUoN1ZJY5beNpmBVjMbzW__vscode-1778309660660/content.txt', langs: ['ar'], flat: true },
    { file: 'toolu_01KUAXXcHyLctB6wEY2EQJeA__vscode-1778309660663/content.txt', langs: ['de', 'fr'] },
    { file: 'toolu_01W8aXAbWZxpHwsUL23FRRnG__vscode-1778309660667/content.txt', langs: ['es', 'ru'] },
    { file: 'toolu_01SWPh5bzAQy9WMTfm7oeqm3__vscode-1778309660668/content.txt', langs: ['it', 'pt'] },
    { file: 'toolu_0117s5ydT8wK1ooWTuvgtZyj__vscode-1778309660669/content.txt', langs: ['ja', 'ko'] },
    { file: 'toolu_01955EP27uEpy2EGWm3v8wZm__vscode-1778309660670/content.txt', langs: ['cs', 'pl'] },
    { file: 'toolu_01XfsMkYxFvUZ8h5v21akZap__vscode-1778309660673/content.txt', langs: ['tr'] },
    { file: 'toolu_01Tp7VexKe6EDQ6rMYqnpvB7__vscode-1778309660674/content.txt', langs: ['uk'] },
    { file: 'toolu_01B7wokmTcFpg34btGyKSH6M__vscode-1778309660675/content.txt', langs: ['el', 'fi'] },
    { file: 'toolu_01CpzMmCJ2bbWLL9tJaYcAL8__vscode-1778309660676/content.txt', langs: ['hu', 'in'] },
    { file: 'toolu_01WDoZLQVHLadSacrKwBBbQu__vscode-1778309660680/content.txt', langs: ['iw', 'ro'] },
    { file: 'toolu_01TcYG21Nasc5S5Vx4ikjCUn__vscode-1778309660739/content.txt', langs: ['vi', 'zh'] },
    { file: 'toolu_01CZxuhTgAkZ48782T7reams__vscode-1778309660683/content.txt', langs: ['da'] },
    { file: 'toolu_018efTx5mw2wVKVqE1sSNvDq__vscode-1778309660684/content.txt', langs: ['nl'] },
    { file: 'toolu_01RBg6Ji6vEuHrBv5DJdHSGX__vscode-1778309660685/content.txt', langs: ['nb'] },
    { file: 'toolu_01G7QWLHpngWnzGrs4BVF3wS__vscode-1778309660686/content.txt', langs: ['sv'] },
];

const pluralKeys = new Set(['settings_live_tv_quick_filters_count', 'settings_timeout_seconds', 'settings_timer_minutes']);

function escapeXml(s) {
    s = s.replace(/&/g, '&amp;');
    s = s.replace(/'/g, "\\'");
    return s;
}

function buildXmlLines(key, value) {
    if (pluralKeys.has(key)) {
        const one = escapeXml(value.one || '');
        const other = escapeXml(value.other || '');
        return [
            `    <plurals name="${key}">`,
            `        <item quantity="one">${one}</item>`,
            `        <item quantity="other">${other}</item>`,
            `    </plurals>`
        ].join('\n');
    } else {
        return `    <string name="${key}">${escapeXml(String(value))}</string>`;
    }
}

// Load all translations
const allTranslations = {};

for (const entry of translationFiles) {
    const filePath = path.join(chatResDir, entry.file.replace(/\//g, path.sep));
    let rawContent = fs.readFileSync(filePath, 'utf8');
    
    // Strip markdown code fences
    rawContent = rawContent.replace(/^```json\s*/m, '').replace(/```\s*$/m, '').trim();
    
    let parsed;
    try {
        parsed = JSON.parse(rawContent);
    } catch (e) {
        console.error(`PARSE ERROR in ${entry.file}: ${e.message}`);
        console.error(`Context: ${rawContent.substring(Math.max(0, e.at - 50), e.at + 100)}`);
        process.exit(1);
    }
    
    for (const lang of entry.langs) {
        let langData = parsed[lang];
        if (!langData && entry.flat && entry.langs.length === 1) {
            langData = parsed; // flat format (no lang wrapper)
        }
        if (!langData) {
            console.warn(`WARNING: Language '${lang}' not found in ${entry.file}`);
            continue;
        }
        allTranslations[lang] = langData;
    }
}

console.log('Loaded translations for:', Object.keys(allTranslations).sort().join(', '));

// Process each locale
for (const lang of Object.keys(allTranslations).sort()) {
    const xmlPath = path.join(resDir, `values-${lang}`, 'strings.xml');
    if (!fs.existsSync(xmlPath)) {
        console.warn(`WARNING: File not found: ${xmlPath}`);
        continue;
    }
    
    const dict = allTranslations[lang];
    const existingContent = fs.readFileSync(xmlPath, 'utf8');
    
    const linesToInsert = [];
    
    for (const key of Object.keys(dict).sort()) {
        // Check if key already exists
        if (existingContent.includes(`name="${key}"`)) {
            continue;
        }
        linesToInsert.push(buildXmlLines(key, dict[key]));
    }
    
    if (linesToInsert.length === 0) {
        console.log(`${lang}: no new keys to inject`);
        continue;
    }
    
    const insertBlock = linesToInsert.join('\n') + '\n';
    const newContent = existingContent.replace('</resources>', insertBlock + '</resources>');
    
    fs.writeFileSync(xmlPath, newContent, 'utf8');
    console.log(`${lang}: injected ${linesToInsert.length} string elements`);
}

console.log('\nDone!');
