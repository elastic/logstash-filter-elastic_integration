import os


class Util:

    @staticmethod
    def get_home_path():
        return os.path.expanduser("~")
