'''
Created on Feb 4, 2011

@author: rca
'''

import multiprocessing
import os

class MontySolrTarget(object):
    """Simple class that represents the target that is registered
    by handlers
    
    It has a str id, which is made of <sender>:<recipient>
        eg. SolrRequestHandler:answer_query
        
        sender = the Java side class (usually)
        recipient = the Python side function name (always)
        
    Where sender may be empty, which means it can be any sender
        eg *:answer_query
        
    """
    def __init__(self, recipient, callable, sender='*'):
        self._recipient = recipient
        self._sender = sender
        self._target = callable
    def getTarget(self):
        return self._target
    def getMessageId(self):
        return '%s:%s' % (self._sender, self._recipient)
    

def make_targets(*args, **kwargs):
    """Creates a list of targets, the keys are the 
    recipient names and the value the callable that
    implements them"""
    targets = []
    assert len(args) % 2 == 0
    i = 0
    while i < len(args):
        name = args[i]
        sender = None
        if ':' in name:
            sender, name = name.split(':')
        if sender:
            targets.append(MontySolrTarget(name, args[i+1], sender=sender))
        else:
            targets.append(MontySolrTarget(name, args[i+1]))
        i += 2
        
    for k,v in kwargs.items():
        targets.append(MontySolrTarget(k, v))
    return targets


def get_numcpus():
    from montysolr import config
    num_cpus = 0
    global api_calls
    # start multiprocessing with that many processes in the pool
    if str(config.MONTYSOLR_MAX_WORKERS) == '-1':
        try:
            num_cpus = multiprocessing.cpu_count()
        except:
            num_cpus = 1
    elif int(config.MONTYSOLR_MAX_WORKERS) > 1:
        num_cpus = int(config.MONTYSOLR_MAX_WORKERS)
    return num_cpus

def multiprocess_aware(original_func):
    def new_func(func_name, *args, **kwargs):
        kwargs['__remote_call'] = True
        return original_func(func_name, *args, **kwargs)
    return new_func
    
    
