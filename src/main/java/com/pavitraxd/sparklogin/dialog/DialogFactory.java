package com.pavitraxd.sparklogin.dialog;

import com.pavitraxd.sparklogin.SparkLoginPlugin;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates built Dialog instances using Paper's official pre-join Dialog builders.
 * Refactored to match modern Paper 1.21.11+ registry-based Dialog API requirements.
 */
public class DialogFactory {
    private final SparkLoginPlugin plugin;
    private final Messages messages;

    // Custom Action identifiers matching Kyori Adventure Keys
    public static final Key LOGIN_SUBMIT = Key.key("sparklogin", "login_submit");
    public static final Key LOGIN_CANCEL = Key.key("sparklogin", "login_cancel");
    public static final Key REGISTER_SUBMIT = Key.key("sparklogin", "register_submit");
    public static final Key REGISTER_CANCEL = Key.key("sparklogin", "register_cancel");
    public static final Key RESET_CONTINUE = Key.key("sparklogin", "reset_continue");

    public DialogFactory(SparkLoginPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
    }

    /**
     * Builds a login dialog with one password text input.
     */
    public Dialog buildLoginDialog(String playerName) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", playerName);

        Component title = messages.getComponent("login.dialog.title", "<gold><bold>SparkLogin - Verification Required</bold></gold>", placeholders);
        Component body = messages.getComponent("login.dialog.body", "<white>Welcome back, <yellow>{player}</yellow>!\nPlease enter your password to authenticate and join the world.</white>", placeholders);
        Component passwordLabel = messages.getComponent("login.dialog.password-label", "<yellow>Enter Password</yellow>", placeholders);
        Component submitLabel = messages.getComponent("login.dialog.submit-button", "<green>Login</green>", placeholders);
        Component cancelLabel = messages.getComponent("login.dialog.cancel-button", "<red>Quit</red>", placeholders);

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("password", passwordLabel).maxLength(64).build());

        DialogBase base = DialogBase.builder(title)
                .inputs(inputs)
                .body(List.of(DialogBody.plainMessage(body)))
                .canCloseWithEscape(false)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .build();

        ActionButton loginButton = ActionButton.builder(submitLabel)
                .action(DialogAction.customClick(LOGIN_SUBMIT, null))
                .build();

        ActionButton cancelButton = ActionButton.builder(cancelLabel)
                .action(DialogAction.customClick(LOGIN_CANCEL, null))
                .build();

        return Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(List.of(loginButton, cancelButton)).build()));
    }

    /**
     * Builds a register dialog with password and password confirmation text inputs.
     */
    public Dialog buildRegisterDialog(String playerName) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", playerName);
        int minLen = plugin.getConfig().getInt("registration.min-password-length", 6);
        int maxLen = plugin.getConfig().getInt("registration.max-password-length", 64);
        placeholders.put("min", String.valueOf(minLen));
        placeholders.put("max", String.valueOf(maxLen));

        Component title = messages.getComponent("register.dialog.title", "<gold><bold>SparkLogin - Account Creation</bold></gold>", placeholders);
        Component body = messages.getComponent("register.dialog.body", "<white>Welcome <yellow>{player}</yellow>!\nYou do not have an account registered yet.\nPlease secure your profile by creating a password below.</white>", placeholders);
        Component passwordLabel = messages.getComponent("register.dialog.password-label", "<yellow>Choose a Password ({min}-{max} characters)</yellow>", placeholders);
        Component confirmLabel = messages.getComponent("register.dialog.confirm-label", "<yellow>Confirm Password</yellow>", placeholders);
        Component submitLabel = messages.getComponent("register.dialog.submit-button", "<green>Register</green>", placeholders);
        Component cancelLabel = messages.getComponent("register.dialog.cancel-button", "<red>Quit</red>", placeholders);

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("password", passwordLabel).maxLength(maxLen).build());

        boolean reqConfirm = plugin.getConfig().getBoolean("registration.require-confirmation-field", true);
        if (reqConfirm) {
            inputs.add(DialogInput.text("confirm_password", confirmLabel).maxLength(maxLen).build());
        }

        DialogBase base = DialogBase.builder(title)
                .inputs(inputs)
                .body(List.of(DialogBody.plainMessage(body)))
                .canCloseWithEscape(false)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .build();

        ActionButton regButton = ActionButton.builder(submitLabel)
                .action(DialogAction.customClick(REGISTER_SUBMIT, null))
                .build();

        ActionButton cancelButton = ActionButton.builder(cancelLabel)
                .action(DialogAction.customClick(REGISTER_CANCEL, null))
                .build();

        return Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(List.of(regButton, cancelButton)).build()));
    }

    /**
     * Builds the single-button informational dialog shown after an admin password
     * reset. Clicking "Continue" leads the player into the standard register dialog.
     */
    public Dialog buildAccountResetDialog(String playerName) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", playerName);

        Component title = messages.getComponent("reset.dialog.title", "<gold><bold>⚠ Account Reset Detected</bold></gold>", placeholders);
        Component body = messages.getComponent("reset.dialog.body",
                "<white>An administrator reset your account.\nYour previous password is no longer valid.\nYou must create a new password before joining the server.</white>", placeholders);
        Component continueLabel = messages.getComponent("reset.dialog.continue-button", "<green>Continue</green>", placeholders);

        DialogBase base = DialogBase.builder(title)
                .body(List.of(DialogBody.plainMessage(body)))
                .canCloseWithEscape(false)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .build();

        ActionButton continueButton = ActionButton.builder(continueLabel)
                .action(DialogAction.customClick(RESET_CONTINUE, null))
                .build();

        return Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.notice(continueButton)));
    }
}
