// Get all the available projects, and display them in the drop down box.
document.addEventListener('DOMContentLoaded', function () {
    var xhttp = new XMLHttpRequest();
    xhttp.onreadystatechange = function() {
        if (this.readyState == 4 && this.status == 200) {
            handleProjectReply(this.responseText);
        }
    };

    xhttp.open("GET", "http://localhost:9080/basched/allprojects", true);
    xhttp.send();
});

function addTask() {
    var task_input = $("#task_name");
    if (task_input.val().trim() != "") {
        var xhttp = new XMLHttpRequest();
        xhttp.onreadystatechange = function() {
            if (this.readyState == 4 && this.status == 201) {
                $("#message").text("Task ["+task_input.val()+"] was added.");
            } else if (this.readyState == 4 && this.status == 409) {
                $("#message").text("Could not add task, reason: Task already exists");
            } else {
                $("#message").text("Could not add task, reason: Something is wrong !!!");
            }
        };

        var projId = $("#project").val();
        var projName = $("#task_name").val();
        var projPri = $("#priority").val();
        xhttp.open("POST", "http://localhost:9080/basched/addTask?prj="+projId+"&name="+projName+"&pri="+projPri, true);
        xhttp.send();
    } else {
        $("#message").text("The task name is empty.");
        task_input.val("");
    }
}

/**
Parses the all projects reply and adds the projects to the project <select> element.
*/
function handleProjectReply(response) {
    var projects = response.split(";");
    var projectOpts = [];
    for (var i = 0; i < projects.length; i++) {
        var prj = projects[i].split(",");
        var prjId = prj[0];
        var prjName = prj[1];
        projectOpts.push("<option value=\""+prjId+"\">"+prjName+"</option>");
    }
    console.log(projectOpts);

    $("#project").append(projectOpts.join(""));
}

function gotoMainPage() {
    window.location.href = 'http://localhost:9080/html/index.html';
}
