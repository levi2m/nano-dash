# Watch app: UI and UX spec

Companion to [WEAR_OS.md](WEAR_OS.md). That file says how the watch talks to the phone; this one says what the watch shows and how it behaves. Target: Galaxy Watch6 Classic (round, 480 x 480), Compose for Wear OS Material 3, dark only.

## 1. Who and when

A rider who has already decided to ride. They glance at the wrist for half a second, in wind and vibration, often in gloves. So:

- **One job per screen, one thing that leads.** The middle of the screen holds a single hero: the word "Ready", then the running time, then a spinner. Nothing else competes with it.
- **Three words of text at most** around the hero. Status is carried by a dot plus a word, never by colour alone.
- **Big targets.** The main button is an edge button across the bottom, at least 60 dp tall (Wear's own minimum is 48 dp; gloves need more).
- **Stopping is deliberate, starting is easy.** Start is one tap. Stop is press and hold, so a bump of the wrist cannot end a ride.
- **Every confirmation is felt, not only seen.** A glove hides the screen but not a buzz.

## 2. Design tokens

Reused from the phone app so the two feel like one product, with one deliberate difference: the ground is true black, because on the watch's OLED display black costs no power and merges with the bezel.

| Token | Value | Used for | Contrast on black (measured) |
|---|---|---|---|
| ground | `#000000` | background | n/a |
| surface | `#232325` | the hold ring track, chips | n/a |
| text | `#EDEDED` | the hero and main words | 17.94:1 |
| text secondary | `#A1A1A6` | status words | 8.16:1 |
| text muted | `#85858B` | hints | 5.73:1 |
| accent (ember) | `#FF7A1A` | the Start button, the recording timer | 8.05:1 |
| on accent | `#000000` | text on the Start button | 8.05:1 |
| ok | `#4CC38A` | GPS fix, camera ready or recording | 9.48:1 |
| warn | `#E3B341` | GPS searching | 10.79:1 |
| bad | `#F2575C` | camera problem, errors | 6.29:1 |

- **Type:** the Wear Material 3 scale. The hero is the largest size on the screen, with tabular figures so the timer does not jitter. Status words use the label size. Sentence case everywhere.
- **Spacing:** a 4 dp grid. The hero sits in the vertical middle; the status line above it; the button at the bottom edge.
- **Shape:** the edge button follows the screen's curve. No cards, no outlines, no shadows, no gradients.
- **Motion:** only the hold ring filling, and the standard spinner. Nothing animates in ambient mode.

## 3. States

| # | State | Hero | Status line | Button |
|---|---|---|---|---|
| 1 | Phone not reachable | "No phone" | "Open Nano Dash on the phone" | Try again |
| 2 | Ready, camera ready | "Ready" | dot green: "Camera ready" | Start ride |
| 3 | Ready, no camera | "Ready" | dot grey: "No camera" | Start ride |
| 4 | Starting (request sent) | spinner | none | none (disabled) |
| 5 | Recording | the running time, in ember | GPS dot + word, camera dot + word | Hold to stop |
| 6 | Stopping (finger down) | the running time, dimmed | none | the ring fills while held |
| 7 | Saving | spinner | "Saving ride" | none |
| 8 | Saved (shown for 2 seconds) | "Saved" | none | none |
| 9 | Error | the reason, short | what to do | Try again |

Starting with no camera is allowed (state 3), exactly as on the phone: the ride is still logged, there is just no video.

### Layouts (round screen, not to scale)

```
 State 2: Ready                State 5: Recording            State 6: Stopping (held)
      .-----------.                 .-----------.                 .-----------.
     /  o Camera   \               / o GPS  o Cam \              /             \
    |    ready      |             |   fix  recording|            |    12:45     |
    |               |             |               |             |   (dimmed)    |
    |     Ready     |             |    12:45      |             |               |
    |               |             |   (ember)     |             |   ring fills  |
     \ [ Start ride ]/             \ [Hold to stop]/             \  around the  /
      '-----------'                 '-----------'                 '-- button --'
```

## 4. Interactions

- **Start:** one tap. State 4 until the phone confirms. Haptic: two short pulses on success.
- **Stop:** press and hold the button for 1.2 seconds. A ring fills around it as you hold, with a light tick at the start. Letting go early cancels, with no message. When the ring completes: one long pulse, then state 7.
- **Hands off:** the app does not keep the screen on. When the wrist drops, Wear OS takes it to ambient mode, which shows only the running time, dim, no colour.
- **Reconnect:** if the phone link drops during a ride, the screen shows state 1, and the ride on the phone keeps going. When the link returns, the screen goes straight back to state 5. Losing the watch never affects the ride.
- **Back and swipe:** the system swipe-to-dismiss works at every state and never changes a ride.

## 5. Copy

Plain words, sentence case, no em dashes, short enough for a 1.47 inch screen. Errors say what happened and what to do.

| Where | Text |
|---|---|
| Hero, not reachable | No phone |
| Hint | Open Nano Dash on the phone |
| Hero, ready | Ready |
| Status, camera | Camera ready / No camera / Camera recording / Camera problem |
| Status, GPS | GPS fix / GPS searching |
| Buttons | Start ride / Hold to stop / Try again |
| Saving | Saving ride |
| Saved | Saved |
| Error: location off | Location is off. Switch it on in the phone's settings |
| Error: no permission | No location access. Allow it in the Nano Dash app |
| Error: still saving | Still saving. Try again in a moment |
| Error: versions differ | Update both apps |
| Error: anything else | Something went wrong. Try again |

## 6. Accessibility

- **Contrast:** every pair in the token table is above 5.7:1.
- **Touch targets:** the main button is at least 60 dp; nothing else is tappable.
- **TalkBack:** the timer reads as "Riding for 12 minutes 45 seconds", the status dots read as their words, and the Stop button exposes a **long-click action labelled "Stop ride"**, so a TalkBack user does not need to find a press-and-hold gesture.
- **Not colour alone:** every dot has a word beside it.
- **Large text:** the layout reflows; the hero scales and the status words wrap, never clip.
- **Haptics:** start, stop and error each have a different pattern, so they can be told apart without looking.

## 7. Not in the first version

Speed, a map, heart rate, a complication, and the tile (milestone 4: the same hero and button, refreshed over the same link). Each is a separate decision, because the half-second glance is the product.
