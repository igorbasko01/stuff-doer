// Get all the available projects, and display them in the drop down box.
document.addEventListener('DOMContentLoaded', function () {
    requestAllProjects();
});

function addTask() {
    var task_input = $("#task_name").val().trim();
    if (task_input != "") {
        var xhttp = new XMLHttpRequest();
        xhttp.onreadystatechange = function() {
            if (this.readyState == 4 && this.status == 201) {
                $("#message").text("Task ["+task_input+"] was added.");
            } else if (this.readyState == 4 && this.status == 409) {
                $("#message").text("Could not add task, reason: Task already exists");
            } else {
                $("#message").text("Could not add task, reason: Something is wrong !!!");
            }
        };

        var projId = $("#project").val();
        var taskName = task_input;
        var taskPri = $("#priority").val();
        xhttp.open("POST", baseURL + "basched/addTask?prj="+projId+"&name="+taskName+"&pri="+taskPri, true);
        xhttp.send();
    } else {
        $("#message").text("The task name is empty.");
        task_input.val("");
    }
}

function addProject() {
    var project_input = $("#project_name").val().trim();
    if (project_input != "") {
        var xhttp = new XMLHttpRequest();
        xhttp.onreadystatechange = function() {
            if (this.readyState == 4 && this.status == 201) {
                $("#message").text("Project ["+project_input+"] was added.");
                requestAllProjects();
            } else if (this.readyState == 4 && this.status == 409) {
                $("#message").text("Could not add project, reason: Project already exists !");
            } else {
                $("#message").text("Could not add project, reason: Something is wrong !!!");
            }
        };

        xhttp.open("POST", baseURL + "basched/addProject?projectName="+project_input);
        xhttp.send();
    } else {
        $("#message").text("The project name is empty.");
        $("#project_name").val("");
    }
}

/**
Parses the all projects reply and adds the projects to the project <select> element.
*/
function handleProjectReply(response) {
    $("#project option").remove();
    var projects = JSON.parse(response).projects;
    var projectOpts = [];
    for (var i = 0; i < projects.length; i++) {
        var prjId = projects[i].id;
        var prjName = projects[i].name;
        projectOpts.push("<option value=\""+prjId+"\">"+prjName+"</option>");
    }
    console.log(projectOpts);

    $("#project").append(projectOpts.join(""));
}

function gotoMainPage() {
    window.location.href = baseURL;
}

function requestAllProjects() {
    var xhttp = new XMLHttpRequest();
    xhttp.onreadystatechange = function() {
        if (this.readyState == 4 && this.status == 200) {
            handleProjectReply(this.responseText);
        }
    };

    xhttp.open("GET", baseURL + "basched/allprojects", true);
    xhttp.send();
}
