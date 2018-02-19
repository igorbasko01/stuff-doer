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
            //TODO: Handle error reply.
            if (this.readyState == 4 && this.status == 200) {
                console.log(this.responseText);
                $("#message").text("Task ["+task_input.val()+"] was added.");
            }
        };

        // TODO: Send real info.
        xhttp.open("POST", "http://localhost:9080/basched/addTask?prj=1&name=aaa&pri=im", true);
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
