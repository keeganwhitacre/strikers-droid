#ifndef PORT_COUCH_SCOREBOOK_H
#define PORT_COUCH_SCOREBOOK_H

struct PortCouchResult
{
    int homeTeam;
    int awayTeam;
    int homeScore;
    int awayScore;
    unsigned homePlayers;
    unsigned awayPlayers;
};

void PortCouchBeginMatch(const short (&sides)[4]);
void PortCouchRecordResult(const PortCouchResult& result);
int PortCouchRecentResults(PortCouchResult* results, int capacity);

constexpr int kCouchRosterSize = 8;
constexpr int kCouchNameLength = 16;
struct PortCouchRoster
{
    char names[kCouchRosterSize][kCouchNameLength + 1];
    int ports[4]; // 0 is a guest, 1–8 are roster entries.
};
void PortCouchLoadRoster(PortCouchRoster& roster);
bool PortCouchSaveRoster(const PortCouchRoster& roster);
void PortCouchRivalry(int first, int second, int& wins, int& losses);

struct PortCouchStanding
{
    int wins = 0;
    int losses = 0;
    int goalsFor = 0;
    int goalsAgainst = 0;
};

enum class PortCouchMode { OpenPlay, Rotation, Series, Cup };

struct PortCouchNight
{
    bool active = false;
    unsigned players = 0;
    PortCouchStanding standings[kCouchRosterSize];
    PortCouchResult recent[5];
    int recentCount = 0;

    PortCouchMode mode = PortCouchMode::OpenPlay;
    unsigned lineup[2]{};
    unsigned waiting = 0;
    int sits[kCouchRosterSize]{};
    int winsNeeded = 0;
    int seriesWins[2]{};
    PortCouchStanding cup[kCouchRosterSize];
    int cupPlayed = 0;
};

const PortCouchNight& PortCouchGetNight();
void PortCouchStartNight(unsigned players);
void PortCouchEndNight();
bool PortCouchDrawTeams(bool balanced);
bool PortCouchStartMode(PortCouchMode mode, int winsNeeded = 0);

#endif
