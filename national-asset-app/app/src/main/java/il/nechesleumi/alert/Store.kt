package il.nechesleumi.alert

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** App state, kept in memory as flows and persisted to SharedPreferences. */
object Store {
    private lateinit var prefs: SharedPreferences

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    private val _settings = MutableStateFlow(Settings())
    private val _event = MutableStateFlow<EventData?>(null)
    val contacts: StateFlow<List<Contact>> = _contacts
    val settings: StateFlow<Settings> = _settings
    val event: StateFlow<EventData?> = _event

    /** Contact currently being called by the phone (null = no call in progress). */
    val calling = MutableStateFlow<String?>(null)

    /** The calling queue is running (incl. waiting between rounds). */
    val dialerRunning = MutableStateFlow(false)

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("neches", Context.MODE_PRIVATE)
        _contacts.value = runCatching { Json.contacts(prefs.getString("contacts", null)) }.getOrDefault(emptyList())
        _settings.value = runCatching { Json.settings(prefs.getString("settings", null)) }.getOrDefault(Settings())
        _event.value = runCatching { Json.event(prefs.getString("event", null)) }.getOrNull()
    }

    // ---------------- contacts

    private fun saveContacts(list: List<Contact>) {
        _contacts.value = list
        prefs.edit().putString("contacts", Json.contacts(list)).apply()
    }

    /** Adds a contact; returns false if the number is already on the list. */
    fun addContact(name: String, phone: String): Boolean {
        val p = phone.trim()
        if (p.isBlank() || _contacts.value.any { Phones.same(it.phone, p) }) return false
        saveContacts(_contacts.value + Contact(UUID.randomUUID().toString(), name.trim().ifBlank { p }, p))
        return true
    }

    fun updateContact(c: Contact) = saveContacts(_contacts.value.map { if (it.id == c.id) c else it })
    fun removeContact(id: String) = saveContacts(_contacts.value.filterNot { it.id == id })

    // ---------------- settings

    fun saveSettings(s: Settings) {
        _settings.value = s
        prefs.edit().putString("settings", Json.settings(s)).apply()
    }

    // ---------------- event

    private fun persistEvent() {
        val e = _event.value
        prefs.edit().apply { if (e == null) remove("event") else putString("event", Json.event(e)) }.apply()
    }

    fun startEvent(mode: Mode): EventData {
        val now = System.currentTimeMillis()
        val ev = EventData(
            id = now, mode = mode, startedAt = now, endedAt = null,
            entries = _contacts.value.map { Entry(it.id, it.name, it.phone) },
        )
        _event.value = ev
        persistEvent()
        return ev
    }

    fun entry(contactId: String): Entry? = _event.value?.entries?.firstOrNull { it.contactId == contactId }

    fun updateEntry(contactId: String, f: (Entry) -> Entry) {
        _event.update { ev -> ev?.copy(entries = ev.entries.map { if (it.contactId == contactId) f(it) else it }) }
        persistEvent()
    }

    fun endEvent() {
        _event.update { it?.copy(endedAt = it.endedAt ?: System.currentTimeMillis()) }
        persistEvent()
    }

    fun clearEvent() {
        _event.value = null
        persistEvent()
    }

    /** An SMS arrived. If it's from someone on the active event's list, mark them as found. */
    fun onIncomingSms(from: String, body: String): Boolean {
        val ev = _event.value ?: return false
        if (!ev.active) return false
        val e = ev.entries.firstOrNull { Phones.same(it.phone, from) } ?: return false
        val now = System.currentTimeMillis()
        updateEntry(e.contactId) { it.copy(replyText = body.trim().take(300), replyAt = it.replyAt ?: now) }
        return true
    }
}
