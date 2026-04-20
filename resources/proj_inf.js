// log_resource.js
function initFilter(root) {
  root = root || document;

  var f = root.querySelector('#filter-input');
  if (!f) return;

  if (f.getAttribute('data-auto-init') === '1') return;
  f.setAttribute('data-auto-init', '1');

  f.value = '';
  f.dispatchEvent(new Event('input', { bubbles: true }));
}

AJS.toInit(function () {
  // open page
  AJS.$(document).off('click', '#sr-proj-inf').on('click', '#sr-proj-inf', function (e) {

    console.log("part1");

    e.preventDefault();
    fetch(AJS.contextPath() + '/rest/scriptrunner/latest/custom/projInfo', {
      method: 'GET',
      headers: { 'Accept': '*/*' },
      credentials: 'include'
    })
    .then(r => r.text())
    .then(html => {
      const main = document.querySelector('#main');
      if (main) {
        main.innerHTML = html;
        initFilter(main);
      }
    });
  });

  // INSERT

  AJS.$(document)
    .off('click', '#send-proj-infoX')
    .on('click', '#send-proj-infoX', function (e) {
      e.preventDefault();

      const v = AJS.$('#project-info-input').val();
      if (!v) {
        alert(' ***  PLEASE ENTER VALUE. *** ');
        return;
      }

      console.log("project-info-input value:", v);
      console.log("Request body:", { projectInformation: v });

      fetch(AJS.contextPath() + '/rest/scriptrunner/latest/custom/insertProjInfo', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': '*/*',
          'X-Atlassian-Token': 'no-check'
        },
        credentials: 'include',
        body: JSON.stringify({ projectInformation: v })
      })
      .then(response => response.text())
      .then(msg => {
        alert(msg);

        // Refresh table
        return fetch(AJS.contextPath() + '/rest/scriptrunner/latest/custom/projInfo', {
          method: 'GET',
          headers: { 'Accept': '*/*' },
          credentials: 'include'
        });
      })
      .then(response => response.text())
      .then(html => {
        const main = document.querySelector('#main');
        if (main) {
          main.innerHTML = html;
          initFilter(main);
        }
      })
      .catch(err => {
        console.error("Fetch error:", err);
        alert("An   !!!ERROR!!!   occurred while processing your request.");
      });
    });


  // REMOVE
  AJS.$(document).off('click', '#remove-proj-infoX').on('click', '#remove-proj-infoX', function (e) {

    console.log("part3");

    e.preventDefault();
    const v = AJS.$('#project-info-input-remove').val();
    if (!v) { alert(' ***  PLEASE ENTER VALUE. *** '); return; }

    fetch(AJS.contextPath() + '/rest/scriptrunner/latest/custom/removeProjInfo', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': '*/*',
        'X-Atlassian-Token': 'no-check'
      },
      credentials: 'include',
      body: JSON.stringify({ projectInformation: v })
    })
    .then(r => r.text())
    .then(msg => {
      alert(msg);
      // refresh
      return fetch(AJS.contextPath() + '/rest/scriptrunner/latest/custom/projInfo', {
        method: 'GET', headers: { 'Accept': '*/*' }, credentials: 'include'
      }).then(r => r.text()).then(html => {
        const main = document.querySelector('#main');
        if (main) {
            main.innerHTML = html;
            initFilter(main);
          }
      });
    });
  });
});
