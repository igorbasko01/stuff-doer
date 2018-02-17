// Get all the available projects, and display them in the drop down box.
document.addEventListener('DOMContentLoaded', function () {
    var xhttp = new XMLHttpRequest();
    xhttp.onreadystatechange = function() {
        if (this.readyState == 4 && this.status == 200) {
            var projects = this.responseText.split(";");
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
    };

    xhttp.open("GET", "http://localhost:9080/basched/allprojects", true);
    xhttp.send();
});

function addTask() {
    console.log("Hello !");
}