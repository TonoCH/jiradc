package rest
/**
 * projInfo
 *
 * @author chabrecek.anton
 * Created on 18. 11. 2025.
 */
import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import groovy.transform.BaseScript
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import com.onresolve.scriptrunner.db.DatabaseUtil

@BaseScript CustomEndpointDelegate delegate

projInfo(httpMethod: "GET", groups: ["jira-administrators-system", "jira-project-information-admins"]) { MultivaluedMap queryParams ->

    def rows = []
    DatabaseUtil.withSql('Project Information 2') { sql ->
        rows = sql.rows('''select distinct "PROJECT_INFORMATION", "ID"
                           from public."AO_601478_COST_CENTRE"
                           order by 1''')
    }

    def sb = new StringBuilder()
    sb.append('''
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <title>Project information</title>
      <style>
        :root{
          --page-bg:#f4f6f8;     /* white page background */
          --card-bg:#ffffff;     /* white cards */
          --text:#1f2937;        /* main font */
          --muted:#5b6472;       /* secondary text */
          --border:#e3e8ef;      /* light border */
          --input-bg:#ffffff;    /* dark input */
          --input-border:#0b1223;
          --accent:#2563eb;      /* primary button */
          --accent-hover:#1d4ed8;
          --danger:#ef4444;      /* remove button */
          --danger-hover:#dc2626;
          --thead:lightslategrey;       /* table header */
          --thead-text:#ffffff;
          --row-alt:#f9fbfd;
          --row-hover:#eef4ff;
        }
        *{box-sizing:border-box}
        body{
          margin:0; padding:24px;
          font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial;
          background: var(--page-bg);
          color:var(--text);
          line-height:1.45;
        }
        .container{
          max-width:margin:auto;
          background:var(--card-bg);
          border:1px solid var(--border);
          border-radius:14px;
          padding:22px;
          box-shadow: 0 6px 18px rgba(0,0,0,.06);
        }
        .title{
          font-weight:800; letter-spacing:.2px;
          font-size: clamp(22px, 2.4vw, 28px);
          text-align:center; margin:4px 0 6px;
          color:var(--text);
        }
        .subtitle{
          text-align:center; color:var(--muted);
          font-size:14px; margin-bottom:18px;
        }

        /* layout */
        .grid{display:grid; gap:14px; grid-template-columns: repeat(12,1fr); align-items:end;}
        .card{grid-column:span 12; background:var(--card-bg); border:1px solid var(--border); border-radius:12px; padding:14px;}
        @media(min-width:760px){ .half{grid-column:span 6;} }

        .label{display:block; font-size:12px; color:#374151; margin-bottom:6px; font-weight:600;}
        .row{display:flex; gap:10px; flex-wrap:wrap;}
        .grow{flex:1 1 auto; min-width:220px;}

        /* inputs */
        .input{
          width:100%; height:40px;
          background:var(--input-bg);
          border:1px solid var(--input-border);
          border-radius:10px; padding:8px 12px; outline:none;
          transition: box-shadow .2s, border-color .2s, transform .05s;
        }
        .input::placeholder{color:#c9d2e6;}
        .input:focus{border-color:#334466; box-shadow:0 0 0 3px rgba(37,99,235,.18);}

        /* buttons */
        .btn{
          height:40px; padding:0 14px; border-radius:10px; border:0; cursor:pointer;
          font-weight:700; color:#fff; transition: transform .05s ease, filter .2s ease, background .2s ease;
        }
        .btn:active{transform:translateY(1px) scale(.99);}
        .btn.primary{background:var(--accent);}
        .btn.primary:hover{background:var(--accent-hover);}
        .btn.danger{background:var(--danger);}
        .btn.danger:hover{background:var(--danger-hover);}
        .btn.secondary{background:steelblue; color:white;}
        .badge{font-size:12px; color:#111827; background:#e5edff; padding:6px 10px; border-radius:999px; border:1px solid #d7e3ff;}

        .meta{color:var(--muted); font-size:12px; margin-top:8px;}

        /* table */
        .table-wrap{margin-top:10px; border-radius:12px; overflow:auto; border:1px solid var(--border);}
        table{width:100%; border-collapse:collapse; min-width:520px;}
        thead th{
          position:sticky; top:0; z-index:1;
          background:var(--thead); color:var(--thead-text);
          font-weight:800; font-size:12px; letter-spacing:.3px;
          text-align:left; padding:11px 12px; border-bottom:1px solid #111827;
        }
        tbody td{
          padding:11px 12px; border-bottom:1px solid var(--border);
          vertical-align:top; color:#111827;
        }
        tbody tr:nth-child(even){ background:var(--row-alt); }
        tbody tr:hover{ background:var(--row-hover); }
        .col-idx{width:70px; color:#334155;}
        .empty{color:#6b7280; font-style:italic;}

        /* filter bar */
        .filterbar{display:flex; gap:10px; align-items:flex-end; flex-wrap:wrap;}
      </style>
    </head>

    
    <body>

      <div class="container">
        <div class="title">Project information</div>
        <div class="subtitle">Manage information from <strong>AO_601478_COST_CENTRE</strong></div>

        <div class="grid" onload="(function(){ var f=document.getElementById('filter-input'); if(f){ f.dispatchEvent(new Event('input')); }})()">
          <div class="card half">
            <label class="label" for="project-info-input">Insert NEW Project Information</label>
            <div class="row">
              <input class="input grow" type="text" id="project-info-input" placeholder="Enter New Project Information">
              <button id="send-proj-infoX" class="btn primary">Insert</button>
            </div>
            <div class="meta">Použitý DB resource: <strong>Project Information 2</strong></div>
          </div>

          <div class="card half">
            <label class="label" for="project-info-input-remove">Remove EXISTING Project Information</label>
            <div class="row">
              <input class="input grow" type="text" id="project-info-input-remove" placeholder="Enter Project Information for REMOVE">
              <button id="remove-proj-infoX" class="btn danger">Remove</button>
            </div>
            <div class="meta">Used DB resource: <strong>Project Information 2</strong></div>
          </div>

          <!-- FILTER (only inline handlers, no <script>) -->
          <div class="card">
            <div class="filterbar">
              
            <div class="grow">
              <label class="label" for="filter-input">Filter rows (PROJECT_INFORMATION)</label>
              <input
                class="input"
                type="text"
                id="filter-input"
                placeholder="Type text contained in Project information"
                value=""
                oninput="
                  (function(el){
                    var v = (el.value||'').toLowerCase().trim();
                    var rows = document.querySelectorAll('#proj-info-table tbody tr[data-txt]');
                    var shown = 0;
                    for (var i=0;i<rows.length;i++){
                      var r = rows[i];
                      var t = r.getAttribute('data-txt')||'';
                      var hit = !v || t.indexOf(v) > -1;
                      r.style.display = hit ? '' : 'none';
                      if (hit) shown++;
                    }
                    var badge = document.getElementById('match-count');
                    if (badge) badge.textContent = shown + (shown===1 ? ' match' : ' matches');

                    var body = document.querySelector('#proj-info-table tbody');
                    var empty = document.getElementById('__noresults');
                    if (shown === 0){
                      if (!empty){
                        empty = document.createElement('tr');
                        empty.id = '__noresults';
                        //empty.innerHTML = <td class='col-idx'>–</td><td class='empty'>No resultss</td>;
                        body.appendChild(empty);
                      }
                    } else if (empty){
                      empty.remove();
                    }
                  })(this)
                "
              >
            </div>

              <button
                id="clear-filter"
                class="btn secondary"
                onclick="
                  (function(){
                    var f=document.getElementById('filter-input');
                    if(f){ f.value=''; f.dispatchEvent(new Event('input')); }
                  })()
                "
              >Clear filter</button>
              <span id="match-count" class="badge">${rows.size()} matches</span>
            </div>
          </div>
        </div>

        <div class="table-wrap">
          <table id="proj-info-table">
            <thead>
              <tr>
                <th>COUNTER</th>
                <th>PROJECT_INFORMATION</th>
                <th>ID</th>
              </tr>
            </thead>
            <tbody>
    ''')

    int i = 1
    rows.each { row ->
        def raw = (row?.PROJECT_INFORMATION ?: '').toString()
        def rawID = (row?.ID ?: '').toString()
        def cellHtml = raw.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;')
        def dataTxt = raw.toLowerCase().replace('"','&quot;').replace('\n',' ').replace('\r',' ')
        sb.append("<tr data-txt=\"${dataTxt}\"><td class='col-idx'>${i++}</td><td>${cellHtml}</td><td>${rawID}</td></tr>")
    }

    if (!rows) {
        sb.append('<tr><td class="col-idx">–</td><td class="empty">No records</td><td>-</td></tr>')
    }

    sb.append("""
            </tbody>
          </table>
        </div>
      </div>
    </body>
    </html>
    """)

    Response.ok().type(MediaType.TEXT_HTML).entity(sb.toString()).build()
}