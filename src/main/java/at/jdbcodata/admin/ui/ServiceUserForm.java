package at.jdbcodata.admin.ui;

import at.jdbcodata.identity.ServiceGroup;
import at.jdbcodata.identity.ServiceUser;
import at.jdbcodata.identity.ServiceUserService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ServiceUserForm {

    @NotBlank @Size(max = 100)
    private String username;
    @Size(max = 200)
    private String displayName;
    private String password;
    private boolean forcePassthrough;
    private boolean enabled = true;
    private List<UUID> groupIds = new ArrayList<>();

    public static ServiceUserForm from(ServiceUser u) {
        ServiceUserForm f = new ServiceUserForm();
        f.username = u.username();
        f.displayName = u.displayName();
        f.forcePassthrough = u.forcePassthrough();
        f.enabled = u.enabled();
        f.groupIds = new ArrayList<>(u.groups().stream().map(ServiceGroup::id).toList());
        return f;
    }

    public ServiceUserService.ServiceUserSpec toSpec() {
        return new ServiceUserService.ServiceUserSpec(username, displayName, password, forcePassthrough, enabled,
                groupIds == null ? List.of() : groupIds);
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isForcePassthrough() { return forcePassthrough; }
    public void setForcePassthrough(boolean forcePassthrough) { this.forcePassthrough = forcePassthrough; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<UUID> getGroupIds() { return groupIds; }
    public void setGroupIds(List<UUID> groupIds) { this.groupIds = groupIds; }
}
