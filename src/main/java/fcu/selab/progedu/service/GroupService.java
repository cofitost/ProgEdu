package fcu.selab.progedu.service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;

import org.gitlab.api.models.GitlabGroup;
import org.gitlab.api.models.GitlabUser;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import fcu.selab.progedu.conn.Conn;
import fcu.selab.progedu.data.Group;
import fcu.selab.progedu.data.Student;
import fcu.selab.progedu.db.GroupDbManager;

@Path("group/")
public class GroupService {

  Conn conn = Conn.getInstance();
  UserService userService = new UserService();
  GroupDbManager gdb = GroupDbManager.getInstance();
  ProjectService projectService = new ProjectService();

  /**
   * upload a csvfile to create group
   * 
   * @param uploadedInputStream file content
   * @param fileDetail          file information
   * @return Response
   */
  @POST
  @Path("upload")
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  public Response upload(@FormDataParam("file") InputStream uploadedInputStream,
      @FormDataParam("file") FormDataContentDisposition fileDetail) {
    Response response;
    boolean isSuccess = false;
    List<String> groupList;
    List<Student> studentList;
    StringBuilder sb = new StringBuilder();
    int read = 0;
    try {
      while ((read = uploadedInputStream.read()) != -1) {
        // converts integer to character and append to StringBuilder
        sb.append((char) read);
      }
      isSuccess = true;
    } catch (IOException e) {

      e.printStackTrace();
    }

    groupList = new ArrayList<>(Arrays.asList(sb.toString().split("\r\n")));
    studentList = map(groupList);
    studentList = sort(studentList);
    List<Group> groups = group(studentList);
    newGroup(groups);

    if (isSuccess) {
      response = Response.ok().build();
    } else {
      response = Response.serverError().status(Status.INTERNAL_SERVER_ERROR).build();
    }
    return response;
  }

  /**
   * map to Student
   * 
   * @param groupList groupList
   * @return studentList studentList
   */
  public List<Student> map(List<String> groupList) {
    List<Student> studentList = new ArrayList<>();
    for (String eachData : groupList) {
      String[] attribute = eachData.split(",");
      if (!attribute[0].equals("Team")) {
        Student student = new Student();
        student.setTeam(attribute[0]);
        // if teamLeader is not empty , this student is teamLeader.
        student.setTeamLeader(!attribute[1].isEmpty());
        student.setStudentId(attribute[2]);
        student.setName(attribute[3]);
        studentList.add(student);
      }

    }
    return studentList;
  }

  /**
   * group
   * 
   * @param studentList sorted studentList
   * @return groupList groupList
   */
  public List<Group> group(List<Student> studentList) {
    List<Group> groupList = new ArrayList<>();
    String groupName = "";
    Group group = null;
    for (int index = 0; index < studentList.size(); index++) {
      if (!groupName.equals(studentList.get(index).getTeam())) {
        if (!(groupName == null || groupName.isEmpty())) {
          groupList.add(group);
        }

        groupName = studentList.get(index).getTeam();
        group = new Group();
        group.setGroupName(groupName);
      }

      if (studentList.get(index).getTeamLeader()) {
        group.setMaster(studentList.get(index).getName());
      } else {
        group.addContributor(studentList.get(index).getName());
      }
    }
    if (!(groupName == null || groupName.isEmpty())) {
      groupList.add(group);
    }

    return groupList;
  }

  /**
   * sort studentList
   * 
   * @param studentList unsorted studentList
   * @return studentList sorted studentList
   */
  public List<Student> sort(List<Student> studentList) {
    Collections.sort(studentList, new Comparator<Student>() {
      @Override
      public int compare(Student s1, Student s2) {
        return s1.getTeam().compareTo(s2.getTeam());
      }
    });
    return studentList;
  }

  /**
   * parse csv file to create a group
   * 
   * @param groups group data
   */
  public void newGroup(List<Group> groups) {
    for (Group group : groups) {
      createGroup(group);
    }
  }

  /**
   * Use GitLab API to create GitlabGroup
   * 
   * @param name The group's name
   * @return GitLabGroup
   */
  public GitlabGroup newGroup(String name) {
    return conn.createGroup(name);
  }

  /**
   * Get new GitLab group id
   * 
   * @param group group on GitLab
   * @return id of GitLab group
   */
  public int newGroupId(GitlabGroup group) {
    return group.getId();
  }

  /**
   * Create group in database
   * 
   * @param group Group in database
   */
  public void createGroup(Group group) {
    int groupId = -1;
    int masterId = -1;
    int developerId = -1;

    groupId = newGroupId(newGroup(group.getGroupName()));

    masterId = findUser(group.getMaster());
    conn.addMember(groupId, masterId, 40); // add member on GitLab
    gdb.addGroup(group.getGroupName(), group.getMaster(), true); // insert into db

    for (String developName : group.getContributor()) {
      developerId = findUser(developName);
      conn.addMember(groupId, developerId, 30); // add member on GitLab
      gdb.addGroup(group.getGroupName(), developName, false); // insert into db
    }

    conn.createGroupProject(group.getGroupName());
  }

  /**
   * Find user by user name
   * 
   * @param name user name
   * @return user id
   */
  public int findUser(String name) {
    List<GitlabUser> users;
    users = conn.getUsers();
    for (GitlabUser user : users) {
      if (user.getName().equals(name)) {
        return user.getId();
      }
    }

    return -1;
  }

  /**
   * Export student list
   * 
   * @return response
   * @throws Exception on file writer call error
   */
  @GET
  @Path("export")
  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  public Response exportStudentList() throws Exception {

    String tempDir = System.getProperty("java.io.tmpdir");

    String downloadDir = tempDir + "/downloads/";

    File fileUploadDir = new File(downloadDir);
    if (!fileUploadDir.exists()) {
      fileUploadDir.mkdirs();
    }

    String filepath = downloadDir + "StdentList.csv";

    final File file = new File(filepath);
    try (final FileWriter writer = new FileWriter(filepath);) {
      StringBuilder build = new StringBuilder();

      String[] csvTitle = { "Team", "TeamLeader", "Student_Id", "name" };

      List<GitlabUser> lsUsers = userService.getUsers();
      Collections.reverse(lsUsers);

      // insert title into file
      for (int i = 0; i < csvTitle.length; i++) {
        build.append(csvTitle[i]);
        if (i == csvTitle.length) {
          break;
        }
        build.append(",");
      }
      build.append("\n");

      // insert user's id and name into file
      for (GitlabUser user : lsUsers) {
        if (user.getId() == 1) {
          continue;
        }
        build.append(""); // Team
        build.append(",");
        build.append(""); // TeamLeader
        build.append(",");
        build.append(user.getUsername()); // userName
        build.append(",");
        build.append(user.getName()); // name
        build.append("\n");
      }
      // write the file
      writer.write(build.toString());
    } catch (Exception e) {
      e.printStackTrace();
    }
    ResponseBuilder response = Response.ok((Object) file);
    response.header("Content-Disposition", "attachment;filename=StudentList.csv");
    return response.build();

  }

  /**
   * Add a new member into a group
   * 
   * @param groupName the group name which new member join
   * @param members   the member name
   */
  @POST
  @Path("add")
  @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
  public Response addMember(@FormParam("groupName") String groupName,
      @FormParam("select2") List<String> members) {
    boolean check = gdb.addGroupMember(groupName, members);
    for (String userName : members) {
      int groupId = conn.getGitlabGroup(groupName).getId();
      int userId = conn.getUserViaSudo(userName).getId();
      conn.addMember(groupId, userId, 30);
    }
    Response response = Response.ok().build();
    if (!check) {
      response = Response.serverError().status(Status.INTERNAL_SERVER_ERROR).build();
    }
    return response;
  }
}