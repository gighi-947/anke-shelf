/**
 * NGA ??/???????? nga_download.js????/??/??/???? section ????
 */
(function () {
  'use strict';

  const { section, fmtBtn, field, input, numInput, select, checkbox, check, val, startDownload, cancelDownload, loadUpdateDefaults, startUpdate, saveConfig, clearConfig, startExport, openExportDest } = window.NgaPage;

  function buildDownloadSection() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';

    wrap.appendChild(field('帖子', input('nga-tid', '帖子 tid 或链接，如 41989465 或 read.php?tid=41989465')));

    const authorRow = field('只看楼主', input('nga-authorid', '楼主 uid（留空=全部楼层）'));
    authorRow.id = 'nga-authorid-row';
    wrap.appendChild(authorRow);

    const row1 = document.createElement('div');
    row1.className = 'nga-form-row';
    row1.append(
      field('前 N 楼', numInput('nga-max-floors', '0', '0=全部')),
      field('每章楼层数', numInput('nga-per-chapter', '20', '')),
    );
    wrap.appendChild(row1);

    const row2 = document.createElement('div');
    row2.className = 'nga-form-row';
    row2.append(
      field('图片', select('nga-image-mode', [
        ['online', '在线图片'],
        ['embedded', '嵌入图片'],
        ['none', '不含图片'],
      ])),
      field('主题', select('nga-theme', [
        ['light', '浅色'],
        ['dark', '深色'],
      ])),
    );
    wrap.appendChild(row2);

    const row3 = document.createElement('div');
    row3.className = 'nga-form-row';
    row3.append(
      field('目录楼 pid', numInput('nga-toc-pid', '0', '安科目录楼 pid，0=无')),
      field('目录用途', select('nga-toc-mode', [
        ['index', '仅作索引'],
        ['split', '兼作分章'],
      ])),
      field('完成后打开', checkbox('nga-open-after', true)),
    );
    wrap.appendChild(row3);

    const row4 = document.createElement('div');
    row4.className = 'nga-form-row';
    row4.append(field('全量重下', checkbox('nga-full', false, '清除本地缓存后重新下载')));
    wrap.appendChild(row4);

    const tocHint = document.createElement('p');
    tocHint.className = 'muted settings-hint';
    tocHint.textContent = '目录用途：仅作索引=仍按每章楼层数分章（目录只用于导出/侧栏索引）；兼作分章=按目录章节切分章节（未提供目录楼时仍按楼层数分章）。';
    wrap.appendChild(tocHint);

    const actions = document.createElement('div');
    actions.className = 'nga-actions';
    const startBtn = document.createElement('button');
    startBtn.className = 'btn btn-primary';
    startBtn.id = 'nga-start';
    startBtn.textContent = '开始下载';
    startBtn.addEventListener('click', startDownload);
    const cancelBtn = document.createElement('button');
    cancelBtn.className = 'btn';
    cancelBtn.id = 'nga-cancel';
    cancelBtn.textContent = '取消任务';
    cancelBtn.disabled = true;
    cancelBtn.addEventListener('click', cancelDownload);
    actions.append(startBtn, cancelBtn);
    wrap.appendChild(actions);

    // 下载状态卡片（下载/更新共用，更新启动后自动切回本页查看）
    const status = document.createElement('div');
    status.className = 'nga-progress';
    status.id = 'nga-progress';
    const stage = document.createElement('div');
    stage.className = 'nga-progress-stage';
    stage.id = 'nga-progress-stage';
    stage.textContent = '当前无下载任务';
    const track = document.createElement('div');
    track.className = 'nga-progress-track';
    const fill = document.createElement('div');
    fill.className = 'nga-progress-fill';
    fill.id = 'nga-progress-fill';
    track.appendChild(fill);
    const text = document.createElement('div');
    text.className = 'nga-progress-text';
    text.id = 'nga-progress-text';
    status.append(stage, track, text);
    wrap.appendChild(status);

    return section('帖子下载', wrap);
  }

  function buildUpdateSection() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';

    const bookSel = select('dl-update-book', []);
    wrap.appendChild(field('帖子', bookSel));
    bookSel.addEventListener('change', () => loadUpdateDefaults(val('dl-update-book')));

    const row1 = document.createElement('div');
    row1.className = 'nga-form-row';
    row1.append(
      field('只看楼主 uid', numInput('dl-update-authorid', '0', '0=全部楼层')),
    );
    wrap.appendChild(row1);

    const row2 = document.createElement('div');
    row2.className = 'nga-form-row';
    row2.append(
      field('主题', select('dl-update-theme', [
        ['light', '浅色'],
        ['dark', '深色'],
      ])),
      field('图片', select('dl-update-image-mode', [
        ['online', '在线图片'],
        ['embedded', '嵌入图片'],
        ['none', '不含图片'],
      ])),
    );
    wrap.appendChild(row2);

    const row3 = document.createElement('div');
    row3.className = 'nga-form-row';
    row3.append(
      field('每章楼层数', numInput('dl-update-per-chapter', '20', '')),
      field('目录楼 pid', numInput('dl-update-toc-pid', '0', '0=无')),
    );
    wrap.appendChild(row3);

    const actions = document.createElement('div');
    actions.className = 'nga-actions';
    const startBtn = document.createElement('button');
    startBtn.className = 'btn btn-primary';
    startBtn.id = 'dl-update-start';
    startBtn.textContent = '开始更新';
    startBtn.addEventListener('click', startUpdate);
    actions.appendChild(startBtn);
    wrap.appendChild(actions);

    const hint = document.createElement('p');
    hint.className = 'muted settings-hint';
    hint.textContent = '更新设置仅对本次新增楼层生效，不影响已有楼层；开始后将自动切换到「下载」页查看进度。';
    wrap.appendChild(hint);

    return section('更新帖子', wrap);
  }

  function buildConfigSection() {
    const cfgBox = document.createElement('div');
    cfgBox.className = 'settings-controls';
    cfgBox.append(
      field('ngaPassportUid', input('nga-cfg-uid', '浏览器 Cookie 中的 ngaPassportUid')),
      field('ngaPassportCid', input('nga-cfg-cid', '浏览器 Cookie 中的 ngaPassportCid')),
      field('User-Agent', input('nga-cfg-ua', '浏览器 UA（已默认填入）')),
    );
    const hint = document.createElement('p');
    hint.className = 'muted settings-hint';
    hint.textContent = 'Cookie 仅保存在本机数据目录，用于访问需要登录可见的帖子；发行版不包含任何个人登录配置。';
    cfgBox.appendChild(hint);
    const btns = document.createElement('div');
    btns.className = 'nga-actions';
    const save = document.createElement('button');
    save.className = 'btn';
    save.textContent = '保存配置';
    save.addEventListener('click', saveConfig);
    const clear = document.createElement('button');
    clear.className = 'btn btn-danger';
    clear.textContent = '清除已保存配置';
    clear.addEventListener('click', clearConfig);
    btns.append(save, clear);
    cfgBox.appendChild(btns);
    return section('NGA 登录配置（Cookie）', cfgBox);
  }

  function buildExportSection() {
    const wrap = document.createElement('div');
    wrap.className = 'settings-controls';

    const bookSel = select('dl-export-book', []);
    wrap.appendChild(field('帖子', bookSel));
    const emptyHint = document.createElement('p');
    emptyHint.className = 'muted settings-hint';
    emptyHint.id = 'dl-export-empty';
    emptyHint.textContent = '暂无已下载的 NGA 帖子，请先完成一次下载。';
    emptyHint.classList.add('hidden');
    wrap.appendChild(emptyHint);

    const fmtRow = document.createElement('div');
    fmtRow.className = 'nga-form-row';
    const fmtWrap = document.createElement('div');
    fmtWrap.className = 'settings-control-inline';
    const fmtLabel = document.createElement('span');
    fmtLabel.className = 'settings-label';
    fmtLabel.textContent = '格式';
    const epubBtn = fmtBtn('EPUB', 'epub');
    const mdBtn = fmtBtn('Markdown', 'md');
    const bothBtn = fmtBtn('两者', 'both');
    fmtWrap.append(fmtLabel, epubBtn, mdBtn, bothBtn);
    fmtRow.appendChild(fmtWrap);
    wrap.appendChild(fmtRow);

    const actions = document.createElement('div');
    actions.className = 'nga-actions';
    const start = document.createElement('button');
    start.className = 'btn btn-primary';
    start.id = 'dl-export-start';
    start.textContent = '开始导出';
    start.addEventListener('click', startExport);
    const cancelExport = document.createElement('button');
    cancelExport.className = 'btn';
    cancelExport.id = 'dl-export-cancel';
    cancelExport.textContent = '取消导出';
    cancelExport.disabled = true;
    cancelExport.addEventListener('click', () => {
      Api.exportCancel().catch(() => {});
    });
    const openDest = document.createElement('button');
    openDest.className = 'btn';
    openDest.id = 'dl-export-open';
    openDest.textContent = '打开文件夹';
    openDest.disabled = true;
    openDest.addEventListener('click', openExportDest);
    actions.append(start, cancelExport, openDest);
    wrap.appendChild(actions);

    const status = document.createElement('div');
    status.className = 'nga-progress';
    status.id = 'dl-export-status';
    const stage = document.createElement('div');
    stage.className = 'nga-progress-stage';
    stage.id = 'dl-export-stage';
    stage.textContent = '暂无导出任务';
    const track = document.createElement('div');
    track.className = 'nga-progress-track';
    const fill = document.createElement('div');
    fill.className = 'nga-progress-fill';
    fill.id = 'dl-export-fill';
    track.appendChild(fill);
    const text = document.createElement('div');
    text.className = 'nga-progress-text';
    text.id = 'dl-export-text';
    status.append(stage, track, text);
    wrap.appendChild(status);

    return section('导出帖子', wrap);
  }


  window.NgaPanels = { buildDownloadSection, buildUpdateSection, buildExportSection, buildConfigSection };
})();
