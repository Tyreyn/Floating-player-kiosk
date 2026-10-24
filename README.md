# Pływający odtwarzacz (nad aplikacjami)

Wtyczka do [Kiosk Satellite](https://kiosksatellite.com), która pokazuje małe okienko odtwarzacza Music Assistant
**nad innymi aplikacjami** – Chrome, SmartTube, YouTube, Stremio. Wbudowany pływający odtwarzacz Kiosk Satellite
znika, gdy kiosk schodzi w tło; ten zostaje.

## Co robi

- Okładka, tytuł, wykonawca, pasek postępu i przyciski ⏮ ⏯ ⏭.
- Pokazuje się tylko wtedy, gdy na odtwarzaczu jest utwór i Kiosk Satellite jest w tle. Na samym kiosku się chowa
  (można to zmienić w ustawieniach).
- Okienko przeciąga się za okładkę albo tytuł; pozycja zostaje zapamiętana.
- Sterowanie i dane idą przez połączenie Kiosk Satellite z Music Assistant – bez dodatkowego tokenu.

## Ustawienia

| Ustawienie | Opis |
|---|---|
| Odtwarzacz Music Assistant | ID odtwarzacza (domyślnie `up3cd38586`). |
| Pokazuj także na kiosku | Okienko również nad dashboardem. |
| Pozycja X / Y | Zapisywane po przeciągnięciu; -1 = prawy dolny róg. |

## Wymagania

- Kiosk Satellite z obsługą wtyczek (SDK 1) i skonfigurowanym Music Assistant.
- Uprawnienie „Wyświetlanie nad innymi aplikacjami” dla Kiosk Satellite (aplikacja zwykle już je ma).

Okienko jest zwykłym oknem systemowym Androida tworzonym przez wtyczkę w procesie Kiosk Satellite; SDK oficjalnie
opisuje tylko nakładki wewnątrz kiosku.

## Licencja

Apache-2.0. Szablon i SDK: [kiosk-satellite-plugin-hello-world](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world).
