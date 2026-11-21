import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Unicast or multicast for a v3 server, as a segmented choice like the
 * codec one, with one plain line under it on what the choice means.
 * [lockedBy] names the protocols that need multicast; while any is on, the
 * choice is fixed and the line says why. The same picker as on Android.
 */
@Composable
fun DeliveryPicker(
    shared: Boolean,
    lockedBy: List<String>,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val locked = lockedBy.isNotEmpty()
    val on = shared || locked
    val hint = when {
        locked -> Strings.get("w3u_delivery_locked", ProtocolStatus.join(lockedBy, Strings.get("list_and")))
        on -> Strings.get("w3u_delivery_all_note")
        else -> Strings.get("w3u_delivery_each_note")
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Overline(Strings.get("w3u_delivery_title"))
        ChoiceGroup(
            choices = listOf(
                Choice(false, Strings.get("w3u_delivery_each"), Icons.Outlined.Person),
                Choice(true, Strings.get("w3u_delivery_all"), Icons.Outlined.Groups)
            ),
            selected = on,
            onSelect = onChange,
            enabled = !locked
        )
        AnimatedContent(
            targetState = hint,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) },
            label = "deliveryHint"
        ) { text -> Hint(text) }
    }
}
