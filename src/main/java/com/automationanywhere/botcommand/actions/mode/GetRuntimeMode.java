package com.automationanywhere.botcommand.actions.mode;

import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.utilities.mode.RuntimeMode;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.annotations.rules.SessionAllowNonExistent;
import com.automationanywhere.commandsdk.annotations.rules.SessionObject;
import com.automationanywhere.commandsdk.model.AttributeType;
import com.automationanywhere.commandsdk.model.DataType;

@BotCommand
@CommandPkg(label = "Runtime mode: get", name = "mode_get", group_label = "Runtime mode",
        description = "Get the runtime mode, or the default when the session does not exist.",
        icon = "log_session.svg", node_label = "{{session}} → {{returnTo}}",
        return_type = DataType.STRING, return_name = "mode", return_required = true,
        documentation_url = "https://github.com/A360-Tools/Bot-Framework/blob/main/docs/mode/GetRuntimeMode.md")
public class GetRuntimeMode {
    @Execute
    public StringValue get(
            @Idx(index = "1", type = AttributeType.SESSION)
            @Pkg(label = "Runtime mode session", default_value_type = DataType.SESSION, default_value = "runtimeMode")
            @SessionObject @SessionAllowNonExistent RuntimeMode session,
            @Idx(index = "2", type = AttributeType.TEXT)
            @Pkg(label = "Default mode", default_value_type = DataType.STRING, default_value = "DEV",
                    description = "DEV, QA or PRD, used when the session does not exist.")
            @NotEmpty String defaultMode) {
        return new StringValue(RuntimeMode.effectiveMode(session, defaultMode));
    }
}
