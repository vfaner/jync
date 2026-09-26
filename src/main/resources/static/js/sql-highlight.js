/* ==========================================================================
   SQL 语法高亮：客户端轻量分词器，配色参考 DBeaver 编辑器。
   纯原生 ES5、零依赖、全部本地资源 —— 内网离线环境同样可用。
   作用于所有 pre.code-block（转换详情页的原始定义 / 自动转换）。
   ========================================================================== */
(function () {
  'use strict';

  /* 关键字 + 常用类型名：命中即加粗蓝字。覆盖过程化语法（WHILE/DECLARE/CURSOR…），
     因为转换详情里展示的多是存储过程正文。 */
  var KW = {};
  ('SELECT FROM WHERE INSERT INTO VALUES UPDATE SET DELETE CREATE REPLACE ALTER DROP TRUNCATE RENAME ' +
   'TABLE INDEX VIEW PROCEDURE FUNCTION TRIGGER DATABASE SCHEMA SEQUENCE ' +
   'PRIMARY KEY FOREIGN REFERENCES NOT NULL DEFAULT UNIQUE CHECK CONSTRAINT IF EXISTS ' +
   'BEGIN END WHILE DO LOOP REPEAT UNTIL LEAVE ITERATE CASE WHEN THEN ELSE ELSEIF ELSIF ' +
   'RETURN RETURNS DECLARE CURSOR OPEN FETCH CLOSE COMMIT ROLLBACK SAVEPOINT TRANSACTION START WORK ' +
   'GRANT REVOKE AS AND OR IN IS LIKE REGEXP BETWEEN JOIN LEFT RIGHT INNER OUTER FULL CROSS ON ' +
   'GROUP BY ORDER HAVING LIMIT OFFSET UNION ALL DISTINCT ASC DESC WITH RECURSIVE ' +
   'EXECUTE CALL HANDLER FOR EACH ROW SIGNAL RESIGNAL RAISE EXCEPTION LANGUAGE ' +
   'DETERMINISTIC CONTAINS READS MODIFIES NO SQL DATA SECURITY DEFINER INVOKER COMMENT OUT INOUT ' +
   'MERGE USING MATCHED TEMP TEMPORARY GLOBAL LOCAL SESSION ' +
   'OVER PARTITION WINDOW RANGE ROWS PRECEDING FOLLOWING CURRENT UNBOUNDED NULLS FIRST LAST ' +
   'INT INTEGER BIGINT SMALLINT TINYINT MEDIUMINT DECIMAL NUMERIC FLOAT DOUBLE REAL ' +
   'VARCHAR NVARCHAR CHAR NCHAR TEXT TINYTEXT MEDIUMTEXT LONGTEXT ' +
   'DATE DATETIME TIMESTAMP TIME YEAR BOOLEAN BOOL BLOB CLOB JSON ENUM BINARY VARBINARY ' +
   'DUAL AUTONOMOUS_TRANSACTION PIPELINED'
  ).split(' ').forEach(function (w) { KW[w] = 1; });

  /* 单遍扫描：字符串 → 引号标识符 → 注释 → 变量 → 数字 → 单词 → 兜底单字符。
     顺序即优先级；'' / "" / ``  doubling 转义都能正确吞下。 */
  var RE = /('(?:''|[^'])*'?)|("(?:""|[^"])*"?|`(?:``|[^`])*`?)|(--[^\n]*|#[^\n]*|\/\*[\s\S]*?(?:\*\/|$))|([@:]{1,2}[A-Za-z_][A-Za-z0-9_$]*)|(\d+(?:\.\d+)?|\.\d+)|([A-Za-z_][A-Za-z0-9_$]*)|([\s\S])/g;

  function esc(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function highlight(src) {
    var out = [];
    var last = 0;
    var m;
    RE.lastIndex = 0;
    while ((m = RE.exec(src)) !== null) {
      if (m.index > last) out.push(esc(src.slice(last, m.index)));
      var tok = m[0];
      if (m[1]) out.push('<span class="sql-str">' + esc(tok) + '</span>');
      else if (m[2]) out.push(esc(tok)); /* 引号标识符：保持正文色，只防误判关键字 */
      else if (m[3]) out.push('<span class="sql-cmt">' + esc(tok) + '</span>');
      else if (m[4]) out.push('<span class="sql-var">' + esc(tok) + '</span>');
      else if (m[5]) out.push('<span class="sql-num">' + esc(tok) + '</span>');
      else if (m[6]) {
        if (KW[tok.toUpperCase()]) out.push('<span class="sql-kw">' + esc(tok) + '</span>');
        else if (src.charAt(RE.lastIndex) === '(') out.push('<span class="sql-fn">' + esc(tok) + '</span>');
        else out.push(esc(tok));
      }
      else out.push(esc(tok));
      last = RE.lastIndex;
      if (tok === '') RE.lastIndex++; /* 零宽防御 */
    }
    if (last < src.length) out.push(esc(src.slice(last)));
    return out.join('');
  }

  function apply() {
    var blocks = document.querySelectorAll('pre.code-block');
    for (var i = 0; i < blocks.length; i++) {
      var el = blocks[i];
      if (el.getAttribute('data-sql-hl')) continue;
      el.setAttribute('data-sql-hl', '1');
      el.innerHTML = highlight(el.textContent);
    }
    var edits = document.querySelectorAll('textarea.sql-edit');
    for (var j = 0; j < edits.length; j++) bindEdit(edits[j]);
  }

  /* 透明 textarea + 底层 pre 垫层：输入与光标归 textarea，着色归 pre，滚动同步 */
  function bindEdit(ta) {
    if (ta.getAttribute('data-sql-hl')) return;
    ta.setAttribute('data-sql-hl', '1');
    var wrap = document.createElement('div');
    wrap.className = 'sql-edit-wrap';
    ta.parentNode.insertBefore(wrap, ta);
    wrap.appendChild(ta);
    var hl = document.createElement('pre');
    hl.className = 'sql-edit-hl';
    hl.setAttribute('aria-hidden', 'true');
    wrap.appendChild(hl);

    function sync() {
      hl.innerHTML = highlight(ta.value);
      hl.scrollTop = ta.scrollTop;
      hl.scrollLeft = ta.scrollLeft;
    }
    ta.addEventListener('input', sync);
    ta.addEventListener('scroll', function () {
      hl.scrollTop = ta.scrollTop;
      hl.scrollLeft = ta.scrollLeft;
    });
    sync();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', apply);
  else apply();

  /* 暴露给控制台调试与后续扩展 */
  window.JyncSQL = { highlight: highlight };
})();
