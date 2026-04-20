// Author: Anton Chabreèek
// Date: December 9, 2024
// Description: The script adds click event listeners to two elements:#released-licences-logs and #logs-download

AJS.$(document).ready(function() {

 console.log("Script loaded version 1.0.6 !");
 
 AJS.$(document).off('click', '#released-licences-logs').on('click', '#released-licences-logs', function(e) {
        e.preventDefault(); 
        console.log("Released licences link clicked!");

        fetch('/rest/scriptrunner/latest/custom/releasedLicenceLog', {
            method: 'GET',
            headers: {
                'Accept': '*/*'
            },
            credentials: 'include'
        })
        .then(response => response.text())
        .then(html => {
            console.log("HTML content fetched successfully.");
            const mainContainer = document.querySelector('#main');
            if (mainContainer) {
                mainContainer.innerHTML = html;
            } else {
                console.warn("No #main container found.");
            }
        })
        .catch(error => {
            console.error('Error fetching logs:', error);
            alert('An error occurred while loading the logs.');
        });
    });
    
 AJS.$(document).off('click', '#logs-download').on('click', '#logs-download', function(e) {
        e.preventDefault(); 
        console.log("Logs download link clicked!");

        fetch('/rest/scriptrunner/latest/custom/logsFromNode', {
            method: 'GET',
            headers: {
                'Accept': '*/*'
            },
            credentials: 'include'
        })
        .then(response => response.text())
        .then(html => {
            console.log("HTML content fetched successfully.");
            const mainContainer = document.querySelector('#main');
            if (mainContainer) {
                mainContainer.innerHTML = html;
            } else {
                console.warn("No #main container found.");
            }
        })
        .catch(error => {
            console.error('Error fetching logs:', error);
            alert('An error occurred while loading the logs download.');
        });
    });

});