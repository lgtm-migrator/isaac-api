/**
 * Copyright 2015 Stephen Cummins
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.cam.cl.dtg.isaac.api.managers;

import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.cam.cl.dtg.isaac.dao.AssignmentPersistenceManager;
import uk.ac.cam.cl.dtg.isaac.dto.AssignmentDTO;
import uk.ac.cam.cl.dtg.segue.api.managers.GroupManager;
import uk.ac.cam.cl.dtg.segue.api.managers.IGroupObserver;
import uk.ac.cam.cl.dtg.segue.api.managers.UserManager;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.NoUserException;
import uk.ac.cam.cl.dtg.segue.comm.EmailManager;
import uk.ac.cam.cl.dtg.segue.dao.ResourceNotFoundException;
import uk.ac.cam.cl.dtg.segue.dao.SegueDatabaseException;
import uk.ac.cam.cl.dtg.segue.dao.content.ContentManagerException;
import uk.ac.cam.cl.dtg.segue.dto.UserGroupDTO;
import uk.ac.cam.cl.dtg.segue.dto.users.RegisteredUserDTO;

import com.google.api.client.util.Lists;
import com.google.inject.Inject;

/**
 * AssignmentManager.
 */
public class AssignmentManager implements IGroupObserver {
    private static final Logger log = LoggerFactory.getLogger(AssignmentManager.class);

    private final AssignmentPersistenceManager assignmentPersistenceManager;
    private final GroupManager groupManager;
    private final EmailManager emailManager;
    private final UserManager userManager;

    /**
     * AssignmentManager.
     * 
     * @param assignmentPersistenceManager
     *            - to save assignments
     * @param groupManager
     *            - to allow communication with the group manager.
     * @param emailManager
     *            - email manager
     * @param userManager
     *            - the user manager object
     */
    @Inject
    public AssignmentManager(final AssignmentPersistenceManager assignmentPersistenceManager,
            final GroupManager groupManager, final EmailManager emailManager, final UserManager userManager) {
        this.assignmentPersistenceManager = assignmentPersistenceManager;
        this.groupManager = groupManager;
        this.emailManager = emailManager;
        this.userManager = userManager;

        groupManager.registerInterestInGroups(this);
    }

    /**
     * Get Assignments set for a given user.
     * 
     * @param user
     *            - to get the assignments for.
     * @return List of assignments for the given user.
     * @throws SegueDatabaseException
     *             - if we cannot complete a required database operation.
     */
    public Collection<AssignmentDTO> getAssignments(final RegisteredUserDTO user) throws SegueDatabaseException {
        List<UserGroupDTO> groups = groupManager.getGroupMembershipList(user);

        if (groups.size() == 0) {
            log.debug(String.format("User (%s) does not have any groups", user.getDbId()));
            return Lists.newArrayList();
        }

        List<AssignmentDTO> assignments = Lists.newArrayList();
        for (UserGroupDTO group : groups) {
            assignments.addAll(this.assignmentPersistenceManager.getAssignmentsByGroupId(group.getId()));
        }

        return assignments;
    }

    /**
     * getAssignmentById.
     * 
     * @param assignmentId
     *            to find
     * @return the assignment.
     * @throws SegueDatabaseException
     *             - if we cannot complete a required database operation.
     */
    public AssignmentDTO getAssignmentById(final String assignmentId) throws SegueDatabaseException {
        return this.assignmentPersistenceManager.getAssignmentById(assignmentId);
    }

    /**
     * create Assignment.
     * 
     * @param newAssignment
     *            - to create - will be modified to include new id.
     * @return the assignment object now with the id field populated.
     * @throws SegueDatabaseException
     *             - if we cannot complete a required database operation.
     */
    public AssignmentDTO createAssignment(final AssignmentDTO newAssignment) throws SegueDatabaseException {
        Validate.isTrue(newAssignment.getId() == null, "The id field must be empty.");
        Validate.notNull(newAssignment.getGameboardId());
        Validate.notNull(newAssignment.getGroupId());

        if (assignmentPersistenceManager.getAssignmentsByGameboardAndGroup(newAssignment.getGameboardId(),
                newAssignment.getGroupId()).size() != 0) {
            log.error(String.format("Duplicated Assignment Exception - cannot assign the same work %s to a group %s", 
                    newAssignment.getGameboardId(), newAssignment.getGroupId()));
            throw new DuplicateAssignmentException("You cannot assign the same work to a group more than once.");
        }

        newAssignment.setCreationDate(new Date());
        newAssignment.setId(this.assignmentPersistenceManager.saveAssignment(newAssignment));
        return newAssignment;
    }

    /**
     * Assignments set by user.
     * 
     * @param user
     *            - who set the assignments
     * @return the assignments.
     * @throws SegueDatabaseException
     *             - if we cannot complete a required database operation.
     */
    public List<AssignmentDTO> getAllAssignmentsSetByUser(final RegisteredUserDTO user) throws SegueDatabaseException {
        Validate.notNull(user);
        return this.assignmentPersistenceManager.getAssignmentsByOwner(user.getDbId());
    }

    /**
     * Assignments set by user and group.
     * 
     * @param user
     *            - who set the assignments
     * @param group
     *            - the group that was assigned the work.
     * @return the assignments.
     * @throws SegueDatabaseException
     *             - if we cannot complete a required database operation.
     */
    public List<AssignmentDTO> getAllAssignmentsSetByUserToGroup(final RegisteredUserDTO user, final UserGroupDTO group)
            throws SegueDatabaseException {
        Validate.notNull(user);
        Validate.notNull(group);
        return this.assignmentPersistenceManager.getAssignmentsByOwnerIdAndGroupId(user.getDbId(), group.getId());
    }

    /**
     * deleteAssignment.
     * 
     * @param assignment
     *            - to delete (must have an id).
     * @throws SegueDatabaseException
     *             - if we cannot complete a required database operation.
     */
    public void deleteAssignment(final AssignmentDTO assignment) throws SegueDatabaseException {
        Validate.notNull(assignment);
        Validate.notBlank(assignment.getId());
        this.assignmentPersistenceManager.deleteAssignment(assignment.getId());
    }

    /**
     * findAssignmentByGameboardAndGroup.
     * 
     * @param gameboardId
     *            to match
     * @param groupId
     *            group id to match
     * @return assignment or null if none matches the parameters provided.
     * @throws SegueDatabaseException
     *             - if we cannot complete a required database operation.
     */
    public AssignmentDTO findAssignmentByGameboardAndGroup(final String gameboardId, final String groupId)
            throws SegueDatabaseException {
        List<AssignmentDTO> assignments = this.assignmentPersistenceManager.getAssignmentsByGameboardAndGroup(
                gameboardId, groupId);

        if (assignments.size() == 0) {
            return null;
        } else if (assignments.size() == 1) {
            return assignments.get(0);
        }

        throw new SegueDatabaseException(String.format(
                "Duplicate Assignment (group: %s) (gameboard: %s) Exception: %s", groupId, gameboardId, assignments));
    }

    /**
     * findGroupsByGameboard.
     * 
     * @param user
     *            - owner of assignments (teacher)
     * @param gameboardId
     *            - the gameboard id to query
     * @return Empty List if none or a List or groups.
     * @throws SegueDatabaseException
     *             - If something goes wrong with database access.
     */
    public List<UserGroupDTO> findGroupsByGameboard(final RegisteredUserDTO user, final String gameboardId)
            throws SegueDatabaseException {
        Validate.notNull(user);
        Validate.notBlank(gameboardId);

        List<AssignmentDTO> allAssignments = this.getAllAssignmentsSetByUser(user);
        List<UserGroupDTO> groups = Lists.newArrayList();

        for (AssignmentDTO assignment : allAssignments) {
            if (assignment.getGameboardId().equals(gameboardId)) {
                try {
                    groups.add(groupManager.getGroupById(assignment.getGroupId()));
                } catch (ResourceNotFoundException e) {
                    // skip group as it no longer exists.
                    log.warn(String.format("Group (%s) that no longer exists referenced by assignment (%s). Skipping.",
                            assignment.getGroupId(), assignment.getId()));
                }
            }
        }

        return groups;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * uk.ac.cam.cl.dtg.segue.api.managers.IGroupInterest#onGroupMembershipRemoved(uk.ac.cam.cl.dtg.segue.dto.UserGroupDTO
     * , uk.ac.cam.cl.dtg.segue.dto.users.RegisteredUserDTO)
     */
    @Override
    public void onGroupMembershipRemoved(final UserGroupDTO group, final RegisteredUserDTO user) {
        // TODO Auto-generated method stub

    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * uk.ac.cam.cl.dtg.segue.api.managers.IGroupInterest#onMemberAddedToGroup(uk.ac.cam.cl.dtg.segue.dto.UserGroupDTO,
     * uk.ac.cam.cl.dtg.segue.dto.users.RegisteredUserDTO)
     */
    @Override
    public void onMemberAddedToGroup(final UserGroupDTO group, final RegisteredUserDTO user) {
        // Try to email user to let them know
        try {
            RegisteredUserDTO groupOwner = this.userManager.getUserDTOById(group.getOwnerId());

            List<AssignmentDTO> existingAssignments = this.getAllAssignmentsSetByUserToGroup(groupOwner, group);

            emailManager.sendGroupWelcome(user, group, groupOwner, existingAssignments);

        } catch (ContentManagerException e) {
            log.info(String.format("Could not send group welcome email "), e);
            e.printStackTrace();
        } catch (NoUserException e) {
            log.info(String.format("Could not find owner user object of group %s", group.getId()), e);
            e.printStackTrace();
        } catch (SegueDatabaseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }
}
