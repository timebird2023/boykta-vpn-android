{pkgs}: {
  deps = [
    pkgs.python313Packages.aiohttp
    pkgs.python313Packages.cryptography
    pkgs.python313Packages.httpx
    pkgs.python313Packages.python-telegram-bot
    pkgs.jdk17
  ];
}
