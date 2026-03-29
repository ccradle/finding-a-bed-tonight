# For Shelter Coordinators

> This page is written for shelter coordinators and volunteer coordinators. For technical details, see [FOR-DEVELOPERS.md](FOR-DEVELOPERS.md).
>
> **Note on personas:** Sandra Kim and Reverend Alicia Monroe are AI-generated personas defined in [PERSONAS.md](../PERSONAS.md) to guide design decisions. They represent the types of coordinators this platform is built for, not real individuals.

---

## What Is Finding A Bed Tonight?

Finding A Bed Tonight is free software that helps outreach workers find available shelter beds in real time. Instead of calling your shelter at midnight to ask "do you have beds?", an outreach worker can check a live dashboard and see your availability instantly.

Your job: keep your bed count up to date. That is the single most important thing you do in this system.

---

## What Does It Cost?

**The software is free.** It is open-source under the Apache 2.0 license, which means no one pays a licensing fee, ever.

**Someone pays for hosting.** The platform runs on a server, and that server costs money. Typically your Continuum of Care (CoC) or a local agency covers this. The smallest setup costs about $15-30 per month. Your shelter does not pay for hosting unless your organization chooses to.

For a plain-language breakdown, see [What Does Free Mean?](what-does-free-mean.md).

---

## How Do I Update My Bed Count?

Three taps. That is the entire flow.

1. **Open the coordinator dashboard** (you are already logged in if you used the app recently)
2. **Find your shelter** (it appears at the top of your list)
3. **Update the occupied count and tap Save**

The form shows your current numbers. You only change what changed. You get a clear confirmation when the save succeeds. The whole thing takes under 30 seconds.

If your total bed count changes (you added cots, closed a wing), you can update that too. But most days, you are only updating how many beds are currently occupied.

---

## How Often Should I Update?

As often as things change. Two to four times per day is a good rhythm: once in the morning, once in the evening, and any time a large group checks in or out. The more current your numbers, the more outreach workers trust the system and the fewer phone calls you get.

---

## What About Bed Holds?

When an outreach worker finds a bed at your shelter, they can place a hold on it. This means they are bringing someone to you. The default hold lasts 90 minutes. If the person does not arrive, the hold expires automatically and the bed becomes available again.

**Hold duration is configurable.** Your CoC administrator can adjust the default. For hospital discharge planners who need more time, the hold can be set to 2-3 hours. You do not need to do anything to manage holds. They appear as a read-only count on your dashboard so you know how many beds are spoken for.

---

## Can I Edit My Shelter's Details?

Yes. From your dashboard, expand your shelter card and tap **Edit Details**. You can update your shelter's phone number, hours, and operational information — the things that change week to week.

Structural details like your shelter's name, address, and DV status are managed by your CoC administrator. If those need to change, contact them.

---

## What If My Shelter Does Not Have WiFi?

The platform is built as a Progressive Web App (PWA). If you lose your internet connection while updating, the app saves your work locally and sends it when you reconnect. You can also update from a phone using mobile data if your desktop is offline.

If your shelter has no internet at all, talk to your CoC administrator. The system can still list your shelter with a phone number so outreach workers know to call you directly.

---

## Can We Participate Without Using Reservations?

Yes. Your shelter can participate at whatever level makes sense. Some shelters only share bed count updates. Others allow bed holds and reservations. Others are view-only. See the [Partial Participation Guide](partial-participation-guide.md) for details.

---

## What If We Close for the Season?

If your shelter operates seasonally (like a cold-weather shelter), your CoC administrator can mark it as temporarily inactive. Your data stays in the system. When you reopen, everything picks up where you left off.

---

## How Do I Get My Shelter Added?

Contact your CoC administrator. They handle shelter setup, which typically takes about a week:

1. Your CoC admin creates the shelter profile
2. They create your coordinator account
3. You log in and verify your shelter's information
4. You start updating bed counts

You do not need any technical skills. If you can use a website, you can use this.

---

## Who Do I Call If Something Goes Wrong?

Your first contact is your CoC administrator. They manage the system for your community.

For broader issues, the project maintains community support through GitHub. See the [Support Model](support-model.md) for details.

---

## Quick Reference

| Question | Answer |
|---|---|
| What does it cost me? | Nothing. Hosting is covered by your CoC or agency. |
| How many taps to update beds? | Three. |
| How long does a bed hold last? | 90 minutes by default (configurable by your CoC admin). |
| What if I lose internet mid-update? | The app saves locally and syncs when you reconnect. |
| Who sets up my shelter? | Your CoC administrator. |
| Can I just share bed counts, no reservations? | Yes. |

---

*Finding A Bed Tonight -- For Shelter Coordinators*
