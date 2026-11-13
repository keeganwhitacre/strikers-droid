#ifndef PORT_COUCH_SCOREBOOK_H
#define PORT_COUCH_SCOREBOOK_H

struct PortCouchResult
{
    int homeTeam;
    int awayTeam;
    int homeScore;
    int awayScore;
    unsigned homePlayers = 0;
    unsigned awayPlayers = 0;
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

#endif
