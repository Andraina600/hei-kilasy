# Journal IA — C1 : pénalité de retard

Outil utilisé : Claude Code (Claude Opus 5.5).

---

## Échange 1 — Analyse de la demande avant de coder

**Demande** : analyser la consigne C1 avec l'IA avant toute modification, et avoir son avis.

**Proposition** : l'IA a lu tout le code et relevé que la note sur 20 était calculée à **4 endroits**, avec **3 arrondis différents** :

| Endroit | Fichier | Calcul avant C1 |
|---|---|---|
| Correction (réponse + email) | `SubmissionController.grade()` | `Math.round(x * 10) / 10.0` sur des `double` |
| Consultation d'une soumission | `SubmissionController.get()` | idem (dupliqué) |
| Relevé de notes | `GradeReport.build()` | `BigDecimal`, HALF_UP, 1 décimale |
| Export CSV | `CsvExport.export()` | `Math.floor` → **troncature** |

Elle a aussi souligné que le critère « même note partout » n'était **déjà pas respecté avant C1**, à cause du `floor` du CSV. Avec la pénalité ce serait pire : 15,5 × 0,9 = 13,95 donne 13,9 en floor et 14,0 en HALF_UP. En `double`, 13,95 n'est pas représentable exactement, donc `Math.round` peut lui aussi donner 13,9.

Elle a remarqué que les données de démo correspondent exactement aux exemples de la consigne :
- Bob, rendu le 11/11 à 02h → 1 jour → 14,4
- Clara, rendu le 13/11 → 3 jours → 11,2
- David, rendu le 18/11 → 8 jours → 8 (plafond)

L'échéance est une `DATE` sans heure, donc elle tombe à minuit, fin du jour J. Le nombre de « jours entamés » est donc la différence en jours entre la date de soumission et l'échéance. C'est la même logique que le calcul existant de `late`, donc il n'y a pas besoin de toucher au schéma.

**Décision** : analyse acceptée. Trois choix sont restés ouverts (voir échange 3).

**Pourquoi** : le vrai risque de C1 n'est pas la formule de la pénalité, c'est la **règle dupliquée** dans plusieurs fichiers qui ne calculent pas tous de la même façon.

**Vérification** : lecture des fichiers et comparaison des données de `data.sql` avec les exemples de la consigne.

---

## Échange 2 — Relier le dépôt local à mon fork

**Demande** : mon dépôt local pointait encore sur le dépôt du professeur.

**Proposition** : renommer `origin` en `upstream`, puis ajouter mon fork (`Andraina600/hei-kilasy`) comme nouvel `origin`, et faire suivre `origin/main` à `main`.

**Décision** : acceptée.

**Pourquoi** : je pousse sur mon fork, et je peux toujours récupérer les mises à jour du professeur avec `git pull upstream main`.

**Vérification** : `git remote -v` affiche `origin` → mon fork et `upstream` → le dépôt du professeur. `git status -sb` affiche `main...origin/main`.

---

## Échange 3 — Choix de conception

**Demande** : trancher les points ouverts avant de coder.

**Proposition de l'IA** :
1. Pour la formule, deux options. Soit (a) la recopier dans chaque fichier, soit (b) l'écrire une seule fois dans une méthode `static` d'un fichier existant. L'IA penchait pour (a), parce que la règle du module interdit de réorganiser le code.
2. L'email : afficher seulement la note pénalisée, ou aussi la pénalité.
3. L'arrondi : `BigDecimal` HALF_UP à 1 décimale partout (CSV compris), appliqué **une seule fois, après la pénalité**.
4. Ajouter un `max(0, …)` pour garantir une note jamais négative. La base n'a pas de contrainte `CHECK`.
5. Ne rien stocker : la pénalité est calculée à la lecture, à partir de `raw_points`, `submitted_at` et `deadline`.

**Décision** :
- **(a) formule dupliquée** dans les 3 fichiers. **(b) refusée** : elle est plus propre, mais c'est un début de refactorisation, ce que la règle du module interdit. Ce sera un argument pour la clean architecture plus tard.
- L'email **indique la pénalité** de retard, sinon l'étudiant ne comprend pas sa note.
- **Même arrondi partout** (HALF_UP, 1 décimale).
- Points 4 et 5 acceptés.
- Les colonnes du CSV **restent inchangées**, parce que la scolarité l'ouvre dans Excel et un changement de format pourrait casser son usage.
- Commit : la consigne impose le message `C1`, qui n'est pas au format Conventional Commits. La consigne du cours passe avant ma préférence.

**Pourquoi** : le module cherche à faire sentir le coût d'une règle métier éparpillée. La même formule est maintenant recopiée 4 fois.

---

## Échange 4 — Implémentation

**Proposition** : la même formule dans chaque fichier, avec un seul arrondi à la fin :

```java
long daysLate = Math.max(0, ChronoUnit.DAYS.between(deadline, submittedAt.toLocalDate()));
long penaltyPercent = Math.min(daysLate * 10, 50);
BigDecimal grade = raw.multiply(BigDecimal.valueOf(20 * (100 - penaltyPercent)))
        .divide(max.multiply(BigDecimal.valueOf(100)), 1, RoundingMode.HALF_UP)
        .max(BigDecimal.ZERO);
```

Tout le calcul (`raw × 20 × (100 − pénalité) / (max × 100)`) est exact en `BigDecimal`, et l'arrondi n'a lieu qu'une fois, à la fin.

**Point soulevé par l'IA en cours de route** : dans `grade()`, la note de la correction était calculée avec les points **reçus dans la requête**, alors que le relevé et le CSV relisent les points **stockés en base**. Or la colonne `raw_points NUMERIC(5,2)` arrondit à 2 décimales. Exemple : pour 13,249 points envoyés, la base stocke 13,25. La correction aurait affiché 13,2 et le relevé 13,3.

**Décision** : accepté. Les points sont arrondis à 2 décimales (HALF_UP, comme PostgreSQL) **avant** l'`UPDATE`, et la note est calculée à partir de cette valeur arrondie.

J'ai aussi gardé l'ajout d'un champ `penaltyPercent` dans les réponses JSON (correction, consultation, relevé).

---

## Échange 5 — Vérification (manuelle : curl + Mailpit)

Application lancée avec `docker compose up -d` et `mvn spring-boot:run`.

**Exemples de la consigne** (exercice « Tri à bulles », 16/20) :

| Soumission | Rendu le | Retard | `/submissions/{id}` | Relevé | CSV |
|---|---|---|---|---|---|
| Alice | 09/11 18h00 | 0 | 16.0 | 16.0 | 16,0 |
| Felix | 10/11 23h30 (jour de l'échéance) | 0 | — | — | 15,0 (pas pénalisé) |
| Bob | 11/11 02h00 | 1 j | 14.4 | 14.4 | 14,4 |
| Clara | 13/11 10h00 | 3 j | 11.2 | 11.2 | 11,2 |
| David | 18/11 15h00 | 8 j → plafond | 8.0 | 8.0 | 8,0 |

Exercice noté sur 10 (Clara, « Pile et file », 7/10, à l'heure) : 14.0 / 14.0 / 14,0.

**Cas limite d'arrondi** : `PUT /submissions/7/grade {"points": 7.75}`. C'est Bob, « Pile et file », sur 10, 1 jour de retard : 15,5 × 0,9 = 13,95.
- Réponse de la correction : `"grade":14.0,"penaltyPercent":10`
- `GET /submissions/7` : 14.0
- Relevé de Bob : 14.0 (moyenne 14.2)
- CSV : `14,0`
- Email dans Mailpit :
  ```
  Ta note pour « Pile et file » est disponible : 14.0/20.
  Rendu avec 1 jour(s) de retard : pénalité de -10 % appliquée.
  ```

**Points stockés** : `PUT /submissions/5/grade {"points": 13.249}` → la correction affiche 13.3, `GET` affiche 13.3 (rawPoints 13.25) et le CSV affiche 13,3. Les trois sont identiques.

**Note jamais négative** : `{"points": -3}` est refusé (`Les points doivent être entre 0 et 20.0`). Le plafond à −50 % et le `max(0, …)` font qu'une note calculée ne peut pas être négative.

---

## Bilan

**Fichiers modifiés** :
- `src/main/java/hei/school/SubmissionController.java` : pénalité dans la correction (réponse + email avec la ligne de pénalité) et dans la consultation. Les points sont arrondis comme en base avant l'enregistrement.
- `src/main/java/hei/school/GradeReport.java` : pénalité dans le relevé. La moyenne en tient compte automatiquement.
- `src/main/java/hei/school/CsvExport.java` : pénalité ajoutée, et `Math.floor` remplacé par HALF_UP en `BigDecimal`.
- `JOURNAL-IA.md` : ce journal.

**Ce que l'IA a proposé et que je n'ai pas gardé** : la méthode `static` partagée (option b), et l'ajout d'une colonne de pénalité dans le CSV.

**Ce que je retiens** :
- La même règle métier (calcul de la note + retard) est maintenant **recopiée 4 fois**.
- Il y avait un bug d'arrondi **préexistant** (le `floor` du CSV), qui n'est apparu qu'en comparant les 4 endroits.
- Le prochain changement de la règle (par exemple un autre taux de pénalité) devra être fait à 4 endroits sans en oublier un.

**Mauvaises pratiques repérées, hors périmètre, non corrigées** :
- `SELECT max(id)` après un `INSERT` : risque de collision quand deux requêtes arrivent en même temps.
- Le CSV n'échappe pas les `;`.
- `LocalDateTime.now()` dépend du fuseau horaire du serveur.

---
---

# Journal IA — C2 : notification par SMS

Outil utilisé : Claude Code (Claude Opus 5.5).

---

## Échange 1 — Analyse de la demande avant de coder

**Demande** : analyser la consigne C2 avec l'IA avant toute modification.

**Proposition** : l'IA a relevé que :
- le numéro de téléphone était **déjà lu** dans `SubmissionController.grade()` (`st.phone` dans le `SELECT`), mais jamais utilisé ;
- la colonne `phone` peut être `NULL` (schéma), même si tous les étudiants de démo ont un numéro ;
- la note est enregistrée en base **avant** l'envoi de l'email, sans transaction. Si l'envoi du SMS lève une exception, la note reste en base, mais la requête renvoie une **erreur 500** : l'enseignant croirait que la correction a échoué. Il faut donc un `try/catch` autour du SMS, placé **après** l'email pour qu'une panne SMS ne bloque jamais l'email (critère 2) ;
- le critère 3 (« changer de fournisseur sans modifier le code de la notation ») est **incompatible avec un simple `println` dans `grade()`** : il faut que `grade()` appelle un objet dédié à l'envoi du SMS. Cela entre en tension avec la règle du module « ne modifier que les fichiers existants ».

**Décision** : analyse acceptée.

**Pourquoi** : le point difficile de C2 n'est pas d'écrire une ligne de log, c'est de respecter les critères 2 et 3 dans un code où tout est dans le contrôleur.

**Vérification** : lecture de `SubmissionController.java`, `schema.sql` et `data.sql`.

---

## Échange 2 — Où mettre l'envoi du SMS (critère 3)

**Demande** : choisir comment respecter le critère 3 en restant le plus proche possible des règles du module.

**Proposition** : l'IA a proposé trois options :
- **A.** deux nouveaux fichiers : une interface `SmsSender` et une implémentation `LogSmsSender` (recommandée par l'IA) ;
- **B.** l'interface et un `@Bean` déclarés dans `KilasyApplication.java`, pour ne créer aucun fichier ;
- **C.** un `println` directement dans `grade()`, qui ne respecte **pas** le critère 3.

J'ai demandé si l'option B respectait les deux attentes. L'IA a répondu : oui pour la règle « pas de nouveau fichier », mais on ajoute quand même un nouveau type, donc ce n'est pas l'esprit de « pas de nouvelle classe ». **Aucune option ne respecte à 100 % les deux**, parce que le critère 3 impose une abstraction.

**Décision** : **un seul nouveau fichier**, `SmsSender.java` : une classe concrète `@Component` avec une méthode `send(phone, text)` qui écrit le SMS dans les logs. `SubmissionController` l'injecte avec `@Autowired`, comme `mailSender`.

**Pourquoi** : c'est le minimum pour respecter le critère 3. Pour changer de fournisseur, on modifiera seulement `SmsSender.java`, pas `grade()`. Je n'ai pas pris d'interface, pour ajouter le moins de code possible. Limite connue : si un jour on veut plusieurs fournisseurs en même temps (par exemple un faux pour les tests et un vrai en production), il faudra transformer `SmsSender` en interface.

Le `try/catch` est dans `grade()` et pas dans `SmsSender` : le fournisseur signale l'échec en levant une exception, et c'est la notation qui décide que la note reste publiée. Cette règle restera donc valable avec un vrai fournisseur.

**Vérification** : `mvn compile` OK, puis les tests de l'échange 5.

---

## Échange 3 — Format de la note dans le SMS

**Demande** : aucune. C'est l'IA qui a remarqué que l'exemple de la consigne écrit `14,4/20` (virgule), alors que l'email existant écrit `14.4/20` (point).

**Proposition** : l'IA a d'abord recommandé la virgule pour que ça soit conforme à l'exemple.

**Décision** : **le point partout**, comme dans l'email.

**Pourquoi** : Pour que les notes s'affiche de la même façon dans l'email et dans le SMS.

**Vérification** : le SMS affiche `14.4/20` (test 1).

---

## Échange 4 — Étudiant sans numéro de téléphone

**Demande** : je n'ai pas tranché ce point explicitement.

**Proposition** : l'IA a appliqué l'option qu'elle recommandait : si `phone` est vide ou `NULL`, on n'envoie pas de SMS, on écrit une ligne dans les logs, et la note est publiée normalement.

**Décision** : gardé.

**Pourquoi** : sans ce test, on appellerait le fournisseur avec un numéro vide. L'erreur serait rattrapée par le `try/catch`, mais le cas « pas de numéro » n'est pas une panne et ne doit pas être écrit comme une erreur.

**Vérification** : par lecture du code uniquement. Tous les étudiants de démo ont un numéro, donc ce cas n'a pas été testé.

---

## Échange 5 — Vérification manuelle

**Demande** : tester les trois critères (curl + Mailpit + logs).

**Test 1 — cas normal (critère 1)** : `PUT /submissions/2/grade {"points": 16, "feedback": "Bon travail."}` (Bob, « Tri à bulles », 1 jour de retard).
- Réponse : `"grade":14.4, "penaltyPercent":10`
- Logs : `Email de note envoyé à bob@etu.kilasy.test` puis `[SMS] +261 34 00 000 02 : Votre note pour « Tri à bulles » est disponible : 14.4/20.`
- Mailpit : email reçu, comme avant. ✅

**Test 2 — panne du SMS (critère 2)** : j'ai ajouté `throw new RuntimeException("fournisseur SMS indisponible");` dans `SmsSender.send()`, puis `PUT {"points": 12}`.
- Mon premier essai semblait ne pas marcher. L'IA a comparé les heures et trouvé la cause : **je n'avais pas redémarré l'appli** après avoir ajouté le `throw`, donc elle exécutait encore l'ancien code. Après redémarrage :
- Réponse : **HTTP 200**, `"grade":10.8`
- Logs : `Échec de l'envoi du SMS à +261 34 00 000 02 : fournisseur SMS indisponible`
- `GET /submissions/2` : `"grade":10.8`, donc la note est publiée
- Mailpit : email reçu avec 10.8/20. ✅

**Test 3 — preuve par l'absurde** : même panne, mais j'ai retiré temporairement le `try/catch` de `grade()`, puis `PUT {"points": 14}`.
- Réponse : **erreur 500**
- `GET /submissions/2` : `"grade":12.6`. La note était **quand même enregistrée**, mais l'enseignant aurait cru que la correction avait échoué.
- Conclusion : le `try/catch` est bien ce qui fait respecter le critère 2.

**Critère 3** : vérifié par lecture. `grade()` ne fait qu'appeler le fournisseur SMS : le détail de l'envoi est à un seul endroit (d'abord `SmsSender.java`, puis le `@Bean` de `KilasyApplication.java` après la révision de l'échange 6).

**Remise en état** : `throw` retiré, `try/catch` remis, appli redémarrée et note de Bob remise à 16 points avec le commentaire « Bon travail. ». L'IA a vérifié avec `git diff` et `GET /submissions/2` (14.4).

---

## Échange 6 — Révision : plus de nouveau fichier (fait au début de C3)

**Demande** : en relisant C2, j'ai décidé de ne plus créer de fichier, pour respecter la règle du module. L'envoi du SMS doit être déclaré dans `KilasyApplication.java` avec un `@Bean`.

**Proposition** : un `@Bean` doit retourner un type. L'IA a proposé deux options :
- **un `BiConsumer<String, String>` du JDK** (recommandé par l'IA) : le bean est une lambda `(phone, text) -> ...`. Aucune nouvelle classe n'est écrite, puisque `BiConsumer` est une interface fournie par Java ;
- **une classe `static` imbriquée `SmsSender`** dans `KilasyApplication`, plus une méthode `@Bean smsSender()` : pas de nouveau fichier, mais quand même une nouvelle classe.

**Décision** : **le `BiConsumer`**. Concrètement :
- `SmsSender.java` est supprimé ;
- `KilasyApplication` déclare le bean `smsSender()`, qui écrit le SMS dans les logs ;
- dans `SubmissionController`, le champ devient `BiConsumer<String, String> smsSender` et l'appel devient `smsSender.accept(phone, texte)`.

**Pourquoi** :
- C'est la seule option qui respecte la règle du module à la lettre : ni nouveau fichier ni nouvelle classe.
- Le critère 3 reste respecté : pour changer de fournisseur, on modifie seulement le `@Bean`, pas `grade()`.
- Limite connue : `accept(phone, text)` est moins parlant que `send(phone, text)`, et rien n'empêche d'inverser les deux `String`. J'ai mis un commentaire `(phone, text)` sur le bean et sur le champ pour compenser.
- Le `try/catch` reste dans `grade()` : c'est toujours la notation qui décide que la note reste publiée si le SMS échoue.

**Vérification** (après redémarrage de l'appli) :
- `mvn clean compile` OK.
- **Test 1 refait** : `PUT /submissions/2/grade {"points": 16, "feedback": "Bon travail."}` donne HTTP 200 et `"grade":14.4`. Dans les logs : `Email de note envoyé à bob@etu.kilasy.test`, puis `[SMS] +261 34 00 000 02 : Votre note pour « Tri à bulles » est disponible : 14.4/20.` ✅
- **Test 2 refait** : la lambda a été remplacée temporairement par `(phone, text) -> { throw new RuntimeException("fournisseur SMS indisponible"); }`, puis `PUT {"points": 12}`. Résultat : HTTP 200, `GET /submissions/2` donne `"grade":10.8`, l'email part, et les logs affichent `Échec de l'envoi du SMS à +261 34 00 000 02 : fournisseur SMS indisponible`. ✅
- Remise en état : lambda d'origine remise, puis `mvn clean compile` OK.

---

## Bilan

**Fichiers modifiés** :
- `src/main/java/hei/school/KilasyApplication.java` : `@Bean smsSender()`, un `BiConsumer<String, String>` (fournisseur SMS simulé, qui écrit le SMS dans les logs). Au départ, l'envoi était dans un nouveau fichier `SmsSender.java`, supprimé à l'échange 6.
- `src/main/java/hei/school/SubmissionController.java` : injection du fournisseur SMS et envoi du SMS après l'email dans `grade()`, avec un `try/catch` et le cas « pas de numéro ».
- `JOURNAL-IA.md` : cette section.

**Ce que l'IA a proposé et que je n'ai pas gardé** :
- l'interface + l'implémentation en deux fichiers (option A) ;
- la virgule dans le SMS (proposition non demandée par le client) ;
- la classe imbriquée `SmsSender` dans `KilasyApplication` (échange 6).

**Ce que je retiens** :
- Le critère 3 m'a obligé à **créer une structure** que le code n'avait pas. Je l'ai d'abord fait avec un nouveau fichier, puis avec un `BiConsumer` pour respecter la règle. Le code est un peu moins lisible : sans pouvoir créer de classe, on est obligé de détourner un type générique. Dans un code où tout est dans le contrôleur, chaque nouvelle demande de flexibilité oblige à extraire quelque chose.
- Le critère 2 n'était respecté que grâce au `try/catch`. Sans lui, la note était enregistrée **et** l'enseignant recevait une erreur : l'état réel et la réponse ne correspondaient plus.
- Après chaque modification du code, il faut redémarrer l'appli avant de tester.

**Mauvaises pratiques repérées, hors périmètre, non corrigées** :
- L'**email** a exactement le problème que le `try/catch` règle pour le SMS : si l'envoi de l'email échoue, la correction renvoie une 500 alors que la note est enregistrée, et en plus le SMS ne part pas.
- Pas de transaction : la note est enregistrée avant les notifications, et rien ne garantit la cohérence si une étape échoue.
- Les logs passent par `System.out` / `System.err` au lieu d'un logger. Dans la console Windows, les accents s'affichent mal.

---
---

# Journal IA — C3 : exercices minutés

Outil utilisé : Claude Code (Claude Opus 5.5).

---

## Échange 1 — Analyse de la demande avant de coder

**Demande** : analyser la consigne C3 avec l'IA avant toute modification.

**Proposition** : l'IA a relevé que :
- il faut **deux nouvelles données** : la durée de l'exercice (colonne `duration_minutes`) et l'heure à laquelle chaque étudiant ouvre l'exercice. Cette heure ne peut pas aller dans `submission`, parce que la soumission n'existe pas encore au moment de l'ouverture. Il faut donc une table `exercise_start` ;
- le vrai piège est « **en plus ou à la place** de son échéance » : `deadline` est `NOT NULL`, et **tout le code suppose qu'elle existe**. Une échéance vide ferait planter (NullPointerException) `ExerciseController` (liste, détail, création), `SubmissionController` (soumission, consultation, correction), `GradeReport` et `CsvExport` ;
- la pénalité de retard est **recopiée dans 4 fichiers** depuis C1 : le cas « pas d'échéance » devra donc être ajouté 4 fois ;
- les cas limites de `start` : deuxième appel, exercice non minuté, exercice déjà soumis, soumission sans `start` ;
- un risque de décalage horaire de 3 h entre Postgres (UTC) et Java (UTC+3) si `started_at` prenait la valeur `DEFAULT now()` de la base. **Cette affirmation était fausse** (voir échange 3).

**Décision** : analyse acceptée, sauf le point sur le fuseau horaire, que j'ai fait vérifier.

**Pourquoi** : comme en C1, la difficulté n'est pas la nouvelle fonctionnalité, mais tout le code existant qui suppose qu'une échéance existe toujours.

**Vérification** : lecture de tous les fichiers Java, de `schema.sql`, `data.sql` et `compose.yaml`.

---

## Échange 2 — Choix de conception

**Demande** : trancher les cas limites avant de coder.

**Proposition et décision** : j'ai validé les 8 propositions de l'IA :
1. **Deuxième appel à `start`** : le décompte n'est pas relancé, on renvoie l'heure de départ déjà enregistrée (HTTP 200 au lieu de 201).
2. **Soumission sans `start`** sur un exercice minuté : refusée (422).
3. **`start` sur un exercice sans durée** : refusé (422, « cet exercice n'est pas minuté »).
4. **Échéance seule, durée seule, ou les deux** sont autorisées, mais il en faut au moins une. C'est vérifié dans `create()` et par une contrainte `CHECK` dans `schema.sql`.
5. **Pas de marge de tolérance** : la soumission doit arriver avant la fin de la durée.
6. **Ouvrir l'exercice après l'échéance** est autorisé, et la pénalité de retard s'applique normalement (critère 4).
7. **Relevé, exercice ouvert mais pas soumis** : statut `NON RENDU` si la durée est écoulée, sinon `EN COURS`.
8. **Aucun nouveau fichier Java** : seulement des ajouts dans les fichiers existants, plus le nouveau endpoint `start` dans `ExerciseController`.

**Pourquoi** :
- (1) Si `start` relançait le décompte, un étudiant pourrait avoir un temps illimité.
- (2) Sans heure de départ, impossible de vérifier la durée.
- (4) La contrainte `CHECK` protège la règle même si on insère des données sans passer par l'API.
- (5) C'est une question à poser au client : avec la latence du réseau, une soumission envoyée à la dernière seconde peut être refusée.

**Vérification** : voir l'échange 6.

---

## Échange 3 — L'IA s'était trompée sur le fuseau horaire

**Demande** : vérifier l'affirmation de l'échange 1, avant de l'utiliser comme argument.

**Proposition** : l'IA a créé un exercice via `POST /exercises`, puis a comparé sa colonne `created_at` (remplie par le `now()` de Postgres, appelé **depuis l'appli**) avec le `now()` lancé dans `psql` et avec l'horloge du PC.

**Résultat** :
- `created_at` (depuis l'appli) : `07:47:14`, comme l'horloge du PC ;
- `now()` dans `psql` : `04:47:14+00` (UTC).

Le driver JDBC règle le fuseau horaire de la session sur celui de la JVM. Le `now()` de la base est donc à l'heure locale quand il est appelé par l'appli. **Il n'y avait pas de décalage de 3 h.**

**Décision** : je garde quand même `started_at` et `submitted_at` **tous les deux pris côté Java** (`LocalDateTime.now()`), sans `DEFAULT now()` dans la table `exercise_start`.

**Pourquoi** : la raison n'est plus le décalage, mais le fait d'avoir **une seule horloge**. Les deux heures que l'on compare viennent de la même source.

**Vérification** : la requête et les deux valeurs ci-dessus.

---

## Échange 4 — Trois points complémentaires

**Demande** : l'IA m'a signalé trois points qui n'étaient pas encore tranchés.

**Proposition et décision** :
- **`start` sur un exercice déjà soumis** : **409** « Cet étudiant a déjà soumis cet exercice », le même code que pour une double soumission.
- **`durationMinutes` dans le JSON des exercices** : le champ est **visible**, même à `null` pour les anciens exercices. C'est un ajout : un client existant ne casse pas.
- **CSV, exercice sans échéance** : la colonne `echeance` est **vide**. Je n'ajoute pas de colonne « temps utilisé », car le client ne la demande que dans le relevé.

**Pourquoi** : rester cohérent avec l'existant et ne pas faire plus que la demande.

**Vérification** : voir l'échange 6.

---

## Échange 5 — Implémentation

**Proposition** :
- **`schema.sql`** : `deadline` devient facultatif. Ajout de `duration_minutes INTEGER CHECK (> 0)`, d'une contrainte « échéance ou durée », et de la table `exercise_start (exercise_id, student_id, started_at, UNIQUE(exercise_id, student_id))`.
- **`Exercise.java`** : champ `durationMinutes`.
- **`ExerciseController.java`** :
  - la liste et le détail acceptent une échéance vide ;
  - `create()` accepte `durationMinutes`. Il doit être un nombre entier positif, sinon la réponse est 400 au lieu d'une erreur 500 ;
  - nouveau `POST /exercises/{id}/start`, avec `INSERT … ON CONFLICT DO NOTHING`. Si deux appels arrivent en même temps, la base garde la première heure au lieu de planter.
- **`SubmissionController.java`** : dans `submit()`, refus en 422 sans `start` ou après la fin de la durée. Dans `submit()`, `get()` et `grade()`, une échéance vide veut dire « jamais en retard ».
- **`GradeReport.java`** : jointure sur `exercise_start`, statut `EN COURS` et `timeUsed` (temps entre l'ouverture et la soumission). Les clés `durationMinutes`, `startedAt` et `timeUsed` ne sont ajoutées **que pour les exercices minutés**, pour que les lignes des autres exercices ne changent pas.
- **`CsvExport.java`** : pas de pénalité et colonne `echeance` vide quand il n'y a pas d'échéance.

**Décision** : gardé.

**Pourquoi** : ce sont les changements minimaux pour respecter les 5 critères sans réorganiser le code.

**Vérification** : `mvn compile` OK, puis l'échange 6.

---

## Échange 6 — Vérification (curl + psql)

Base remise à zéro avant les tests avec `docker compose down` puis `docker compose up -d`, parce que les scripts SQL ne modifient pas une table qui existe déjà.

**Critère 3 — exercices sans durée inchangés** : **avant** de modifier le code, l'IA a enregistré les réponses de 25 requêtes GET : `/exercises`, `/exercises/1..5`, `/submissions/1..12`, `/students/1..6/transcript` et `/exports/grades.csv`. Elle a refait la même capture **après** les modifications et comparé les deux.
- Relevés, soumissions et CSV : **identiques octet pour octet**.
- Exercices : seule différence, le champ ajouté `"durationMinutes": null`. ✅

**Critère 1 — durée en plus ou à la place de l'échéance** :
- `{"title":"QCM minute","durationMinutes":30}` (durée seule) : 201, `"deadline":null`
- `{"title":"Partiel minute","durationMinutes":60,"deadline":"2027-01-01"}` (les deux) : 201
- `{"title":"Classique","deadline":"2027-01-01"}` (comme avant) : 201
- Ni échéance ni durée : 400. Durée `"abc"`, `0` ou `2.5` : 400.
- Insertion directe en base sans échéance ni durée : refusée par la contrainte `exercise_check`. ✅

**`start`** :
- Exercice non minuté : 422. Exercice ou étudiant introuvable : 404. Sans `studentId` : 400.
- Premier appel : 201. Deuxième appel deux secondes plus tard : 200 avec **le même `startedAt`**.
- Après soumission : 409. ✅

**Critère 2 — soumission après la fin de la durée** :
- Soumission sans `start` : **422** « Exercice minuté : il faut d'abord l'ouvrir (POST /exercises/6/start) avant de soumettre ».
- Pour ne pas attendre 30 minutes, l'heure de départ de Bob a été reculée de 31 minutes avec `psql` (`UPDATE exercise_start SET started_at = started_at - INTERVAL '31 minutes' …`). Sa soumission donne **422** « Temps écoulé : la durée de 30 minute(s) s'est terminée le …, la soumission est refusée ». ✅

**Critère 4 — pénalité de retard sur un exercice minuté avec échéance** : l'API refuse une échéance passée, donc l'échéance de « Partiel minute » a été mise à J-2 avec `psql`. Clara a ouvert l'exercice et soumis 12 minutes plus tard, donc dans la durée de 60 minutes.
- Soumission acceptée : `"late":true`.
- Correction à 20/20 : `"penaltyPercent":20`, `"grade":16.0`. ✅

**Critère 5 — temps utilisé dans le relevé** :
- Clara : `"timeUsed":"12 min 5 s"`, `"status":"CORRIGÉ"`, `"grade":16.0`.
- David (exercice ouvert, pas soumis) : `"status":"EN COURS"`.
- Emma (exercice ouvert il y a 40 minutes, pas soumis) : `"status":"NON RENDU"`.
- Clara n'a pas ouvert « QCM minute » : `"status":"À RENDRE"`, `"startedAt":null`. ✅

**Autres vérifications** : `GET /submissions/13` et la correction d'un exercice sans échéance fonctionnent (pénalité 0). Dans le CSV, la ligne de cet exercice a une colonne `echeance` vide.

---

## Bilan

**Fichiers modifiés** :
- `src/main/resources/schema.sql` : échéance facultative, colonne `duration_minutes`, contrainte « échéance ou durée », table `exercise_start`.
- `src/main/java/hei/school/Exercise.java` : champ `durationMinutes`.
- `src/main/java/hei/school/ExerciseController.java` : lecture avec une échéance vide, création avec une durée, nouveau `POST /exercises/{id}/start`.
- `src/main/java/hei/school/SubmissionController.java` : refus en 422 dans `submit()`, échéance vide dans `submit()`, `get()` et `grade()`.
- `src/main/java/hei/school/GradeReport.java` : temps utilisé, statut `EN COURS`, échéance vide.
- `src/main/java/hei/school/CsvExport.java` : échéance vide.
- `README.md` : endpoint `POST /exercises/{id}/start` dans le tableau de l'API, exemple d'exercice minuté, règles métier (échéance et/ou durée, refus en 422).
- `JOURNAL-IA.md` : cette section.

**Ce que l'IA a proposé et que je n'ai pas gardé** :
- l'argument du décalage horaire de 3 h, qui était faux (échange 3) ;
- une colonne « temps utilisé » dans le CSV.

**Ce que je retiens** :
- Une phrase de la consigne (« ou à la place de son échéance ») a touché **6 fichiers**, parce que tout le code supposait que l'échéance existait toujours.
- La pénalité de retard recopiée en C1 a dû recevoir le même correctif **4 fois**. C'est le coût concret de la duplication.
- Il faut vérifier les affirmations techniques de l'IA : son explication sur le fuseau horaire était convaincante, mais fausse.
- Sans tests automatiques, la capture avant/après des réponses de l'API m'a permis de prouver le critère 3 au lieu de le supposer.

**Mauvaises pratiques repérées, hors périmètre, non corrigées** :
- **N'importe qui peut lancer le chrono d'un autre étudiant** : sans authentification, un étudiant peut appeler `start` avec le `studentId` d'un camarade et lui faire perdre son temps. C3 rend ce manque d'authentification plus grave.
- `start` recopie le code de `submit()` qui cherche l'exercice et l'étudiant. Comme dans `submit()`, un `studentId` envoyé en texte provoque une erreur 500 au lieu d'une 400.
- Toujours `SELECT max(id)` après un `INSERT`, et un risque d'erreur 500 si deux soumissions du même étudiant arrivent en même temps (le contrôle « déjà soumis » se fait avant l'insertion).
- `timeUsed` n'apparaît que pour un exercice soumis. Pour un exercice ouvert mais pas rendu, le relevé montre seulement `startedAt` et le statut.
- Le message d'erreur de `create()` sans échéance a changé : c'est une petite différence, même pour les exercices non minutés.
