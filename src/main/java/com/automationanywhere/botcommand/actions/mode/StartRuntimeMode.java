package com.automationanywhere.botcommand.actions.mode;

import com.automationanywhere.botcommand.data.impl.SessionValue;
import com.automationanywhere.botcommand.utilities.mode.RuntimeMode;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AttributeType;
import com.automationanywhere.commandsdk.model.DataType;
import com.automationanywhere.commandsdk.model.ReturnSettingsType;

@BotCommand
@CommandPkg(label = "Runtime mode: start", name = "mode_start", group_label = "Runtime mode",
        description = "Create a DEV, QA or PRD session. Save as runtimeMode with GLOBAL scope to share with child taskbots.",
        icon = "log_session.svg", node_label = "{{mode}} → {{returnTo}}",
        return_settings = {ReturnSettingsType.SESSION_TARGET}, return_type = DataType.SESSION,
        return_name = "runtimeMode", return_required = true,
        documentation_url = "https://github.com/A360-Tools/Bot-Framework/blob/main/docs/mode/StartRuntimeMode.md")
public class StartRuntimeMode {
    @Execute
    public SessionValue start(
            @Idx(index = "1", type = AttributeType.TEXT)
            @Pkg(label = "Mode", description = "DEV, QA or PRD (case-insensitive; surrounding spaces ignored).")
            @NotEmpty String mode) {
        return SessionValue.builder().withSessionObject(new RuntimeMode(mode)).build();
    }
}
